import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import 'notification_channels_api.dart';
import '../project_access/project_entry.dart';

class NotificationChannelsController extends ChangeNotifier {
  NotificationChannelsController(this.sessions, this.api)
    : scope = sessions.scopeRevision,
      entry = sessions.current?.entry,
      username = sessions.current?.username {
    _token = sessions.current?.accessToken;
    sessions.addListener(_sessionChanged);
  }
  final SessionController sessions;
  final NotificationChannelsApi api;
  final int scope;
  final ProjectEntry? entry;
  final String? username;
  NotificationChannels? data;
  String? error, _token;
  bool loading = false, ended = false, _active = false, _disposed = false;
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
    if (changed && _active && !loading) unawaited(reload());
    notifyListeners();
  }

  void _clear() {
    _generation++;
    data = null;
    error = null;
    loading = false;
    notifyListeners();
  }

  void setActive(bool value) {
    if (_disposed || value == _active) return;
    _active = value;
    _clear();
    if (value) unawaited(reload());
  }

  Future<void> reload() async {
    final project = entry;
    if (_disposed || !_active || !sameSession || project == null) return;
    final generation = ++_generation;
    bool valid() => !_disposed && sameSession && generation == _generation;
    data = null;
    error = null;
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
      // 旧身份或页面已隐藏，不发布结果。
    } catch (failure) {
      if (valid()) {
        error = failure is AppRequestFailure
            ? failure.message
            : '通知渠道暂不可读取，请重试。';
      }
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        notifyListeners();
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
