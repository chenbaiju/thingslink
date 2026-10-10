import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import '../project_access/project_entry.dart';
import 'notification_preferences_api.dart';

/// 服务端确认后才展示新偏好，写入失败只重读，不自动重复写入。
class NotificationPreferencesController extends ChangeNotifier {
  NotificationPreferencesController(this.sessions, this.api)
    : entry = sessions.current?.entry,
      username = sessions.current?.username,
      scope = sessions.scopeRevision {
    _token = sessions.current?.accessToken;
    sessions.addListener(_sessionChanged);
  }
  final SessionController sessions;
  final NotificationPreferencesApi api;
  final ProjectEntry? entry;
  final String? username;
  final int scope;
  NotificationPreferences? data;
  String? error, notice, _token;
  bool loading = false,
      saving = false,
      ended = false,
      _active = false,
      _disposed = false;
  int _generation = 0;
  bool get sameSession =>
      !ended &&
      scope == sessions.scopeRevision &&
      sessions.current?.entry.id == entry?.id &&
      sessions.current?.username == username;
  void _sessionChanged() {
    if (_disposed) return;
    final current = sessions.current;
    if (scope != sessions.scopeRevision ||
        (current == null && !sessions.busy) ||
        (current != null &&
            (current.entry.id != entry?.id || current.username != username))) {
      ended = true;
      _clear();
      return;
    }
    final changed = current != null && _token != current.accessToken;
    if (current != null) _token = current.accessToken;
    if (changed && _active && !loading && !saving) unawaited(reload());
    notifyListeners();
  }

  void _clear() {
    _generation++;
    data = null;
    error = null;
    notice = null;
    loading = false;
    notifyListeners();
  }

  void setActive(bool active) {
    if (_disposed || active == _active) return;
    _active = active;
    _clear();
    if (active && !saving) unawaited(reload());
  }

  Future<void> reload({bool keepNotice = false}) async {
    final project = entry;
    if (_disposed || !_active || !sameSession || project == null || saving) {
      return;
    }
    final generation = ++_generation;
    bool valid() =>
        !_disposed && _active && sameSession && generation == _generation;
    data = null;
    error = null;
    if (!keepNotice) notice = null;
    loading = true;
    notifyListeners();
    try {
      final result = await readWithSession(
        sessions,
        project,
        (token) => api.read(project, token),
        valid,
      );
      if (valid()) data = result;
    } on StaleSessionRead {
      /* 隐藏或身份变化后静默废弃旧结果。 */
    } catch (failure) {
      if (valid()) {
        error = failure is AppRequestFailure
            ? failure.message
            : '通知设置暂不可用，请重试。';
      }
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> save(bool enabled) async {
    final previous = data, project = entry;
    if (_disposed ||
        !_active ||
        !sameSession ||
        saving ||
        loading ||
        previous == null ||
        !previous.editable ||
        project == null ||
        previous.appPushEnabled == enabled) {
      return;
    }
    saving = true;
    error = null;
    notice = null;
    _generation++;
    notifyListeners();
    bool reread = false;
    try {
      final token = await sessions.accessFor(project);
      if (!sameSession || token == null) return;
      final result = await api.update(
        project,
        token,
        enabled,
        previous.revision,
      );
      if (result.appPushEnabled != enabled ||
          BigInt.parse(result.revision) <= BigInt.parse(previous.revision)) {
        throw const AppRequestFailure('保存回执未确认。');
      }
      if (!_disposed && _active && sameSession) {
        data = result;
        notice = '通知偏好已保存。';
      }
    } on AppRequestFailure catch (failure) {
      if (!sameSession) return;
      if (failure.unauthorized) {
        await sessions.logout();
        return;
      }
      if (!_disposed && _active) {
        data = null;
        notice = failure.status == 409
            ? '设置已在其他设备变化，请以重新读取结果为准。'
            : '保存未确认，请以重新读取的服务端状态为准。';
      }
      reread = true;
    } catch (_) {
      if (sameSession) {
        if (!_disposed && _active) {
          data = null;
          notice = '保存未确认，请以重新读取的服务端状态为准。';
        }
        reread = true;
      }
    } finally {
      saving = false;
      if (!_disposed) {
        notifyListeners();
        if (_active && sameSession && (reread || data == null)) {
          await reload(keepNotice: true);
        }
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    sessions.removeListener(_sessionChanged);
    super.dispose();
  }
}
