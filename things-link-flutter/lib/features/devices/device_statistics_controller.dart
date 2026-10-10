import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import 'device_statistics.dart';

/// 统计只在内存保存，项目/身份变化丢弃旧请求；单次读取最多恢复认证一次。
class DeviceStatisticsController extends ChangeNotifier {
  DeviceStatisticsController(this.sessions, this.api, {bool active = true}) {
    _active = active;
    sessions.addListener(_sessionChanged);
    _sessionChanged();
  }
  final SessionController sessions;
  final DeviceStatisticsApi api;
  DeviceStatistics? data;
  String? error;
  bool loading = false;
  String? _identity;
  String? _token;
  String? _target;
  int _generation = 0;
  int? _scopeRevision;
  bool _disposed = false;
  late bool _active;
  void setActive(bool value) {
    if (_disposed || value == _active) return;
    _active = value;
    _clear();
    if (value && sessions.current != null) unawaited(reload());
  }

  String? get _currentIdentity {
    final session = sessions.current;
    return session == null
        ? null
        : '${session.entry.id}\u0000${session.username}';
  }

  void _sessionChanged() {
    if (_disposed) return;
    if (_target != sessions.targetId ||
        _scopeRevision != sessions.scopeRevision) {
      _scopeRevision = sessions.scopeRevision;
      _target = sessions.targetId;
      _clear();
    }
    if (sessions.current == null) {
      if (!sessions.busy) _clear();
      return;
    }
    final identity = _currentIdentity;
    final token = sessions.current!.accessToken;
    final changed = _identity != identity || _token != token;
    if (_identity != identity) _clear();
    _identity = identity;
    _token = token;
    if (_active && changed && !loading) unawaited(reload());
  }

  void _clear() {
    _generation++;
    data = null;
    error = null;
    loading = false;
    _identity = null;
    _token = null;
    if (!_disposed) notifyListeners();
  }

  Future<void> reload() async {
    final session = sessions.current;
    if (session == null || _disposed || !_active) return;
    final identity = _currentIdentity;
    final generation = ++_generation;
    bool valid() =>
        !_disposed && generation == _generation && identity == _currentIdentity;
    loading = true;
    data = null;
    error = null;
    notifyListeners();
    try {
      var token = await sessions.accessFor(session.entry);
      if (!valid() || token == null) return;
      DeviceStatistics result;
      try {
        result = await api.read(session.entry, token);
      } on AppRequestFailure catch (failure) {
        if (!valid()) return;
        if (!failure.unauthorized) rethrow;
        await sessions.refresh();
        if (!valid()) return;
        token = await sessions.accessFor(session.entry);
        if (!valid() || token == null) return;
        try {
          result = await api.read(session.entry, token);
        } on AppRequestFailure catch (retryFailure) {
          if (valid() && retryFailure.unauthorized) await sessions.logout();
          rethrow;
        }
      }
      if (valid()) data = result;
    } on AppRequestFailure catch (failure) {
      if (valid()) error = failure.message;
    } catch (_) {
      if (valid()) error = '设备统计暂不可用，请重试。';
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
