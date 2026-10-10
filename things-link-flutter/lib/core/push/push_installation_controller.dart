import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../network/app_http_client.dart';
import '../session/app_session.dart';
import '../session/session_controller.dart';
import '../session/session_storage.dart';
import 'installation_identity.dart';
import 'push_installation_api.dart';

/// 写入串行且持久记录回执ID；退出待撤销期间不激活新绑定，未知结果仅读回。
class PushInstallationController extends ChangeNotifier {
  PushInstallationController(
    this.sessions,
    this.installation,
    this.api,
    this.source,
    this.storage,
  ) {
    sessions.addListener(_changed);
    _changed();
  }
  final SessionController sessions;
  final InstallationIdentity installation;
  final PushInstallationApi api;
  final PushTokenSource source;
  final SessionStorage storage;
  Future<void> _tail = Future.value();
  String? _observed;
  int _generation = 0;
  bool _disposed = false;
  PushBinding? binding;
  String status = '本机推送暂不可用';
  void _changed() {
    if (!sessions.loaded) return;
    final s = sessions.current;
    final key =
        '${s?.accessToken}/${sessions.scopeRevision}/${sessions.pendingCount}/${sessions.busy}';
    if (key == _observed) return;
    _observed = key;
    _generation++;
    if (s == null || sessions.pendingCount > 0) {
      binding = null;
      status = sessions.pendingCount > 0 ? '旧会话待远端撤销，本机推送暂停' : '本机推送暂不可用';
      _notify();
    }
    if (!sessions.busy) unawaited(synchronize());
  }

  /// 前台恢复与SDK轮换共用入口；调用时捕获会话，晚到结果不发布到新账号。
  Future<void> synchronize() {
    final s = sessions.current, generation = _generation;
    final job = _tail.then((_) async {
      if (_disposed || !sessions.loaded) return;
      if (s == null || sessions.pendingCount > 0) {
        if (!sessions.hasRetainedSession || sessions.pendingCount > 0) {
          try {
            await storage.write('{}');
          } catch (_) {
            status = '无法清理本机推送记录';
            _notify();
          }
        }
        return;
      }
      if (!_valid(s, generation)) return;
      final identity = s.identity;
      if (identity?.backendInstanceId == null) {
        status = '平台暂不支持本机推送关联';
        _notify();
        return;
      }
      try {
        final token = await source.currentToken();
        if (!_valid(s, generation)) return;
        if (token == null) {
          status = '本机推送暂不可用';
          _notify();
          return;
        }
        if (token.value.trim().isEmpty ||
            token.value.length > 4096 ||
            token.channelConfigurationId.trim().isEmpty ||
            token.channelConfigurationId.length > 64) {
          throw const FormatException('厂商推送标识无效');
        }
        final id = await installation.id;
        final raw = await storage.read();
        if (raw != null && raw.length > 32768) {
          throw const FormatException('推送恢复记录过大');
        }
        final checkpoint = raw == null ? <String, dynamic>{} : jsonDecode(raw);
        if (checkpoint is! Map<String, dynamic>) {
          throw const FormatException('推送恢复记录损坏');
        }
        if (!_valid(s, generation)) return;
        final scope = '${s.entry.baseUrl}/${identity!.bindingScope}';
        final same =
            checkpoint['scope'] == scope &&
            checkpoint['token'] == token.value &&
            checkpoint['channel'] == token.channelConfigurationId;
        final previous = await api.read(s, id);
        if (!_valid(s, generation)) return;
        bool matches(PushBinding? result, String? receipt) =>
            result != null &&
            result.installationId == id &&
            result.sessionGroupId == identity.sessionGroupId &&
            result.registrationId == receipt &&
            result.status == 'ACTIVE' &&
            result.leaseExpiresAt.isAfter(DateTime.now());
        // 已确认的每日续约间隔跨重启保持；仍读回服务端，以便发现租约、撤销或占用变化。
        final savedAt = DateTime.tryParse(
          checkpoint['confirmedAt']?.toString() ?? '',
        );
        if (same &&
            matches(previous, checkpoint['receipt'] as String?) &&
            savedAt != null &&
            !savedAt.isAfter(DateTime.now()) &&
            DateTime.now().difference(savedAt) < const Duration(days: 1)) {
          binding = previous;
          status = '安装关联已同步，实际通知由系统通道决定';
          _notify();
          return;
        }
        if (same && checkpoint['pending'] == true) {
          if (matches(previous, checkpoint['receipt'] as String?)) {
            checkpoint['pending'] = false;
            checkpoint['confirmedAt'] = DateTime.now()
                .toUtc()
                .toIso8601String();
            await storage.write(jsonEncode(checkpoint));
            if (_valid(s, generation)) {
              binding = previous;
              status = '安装关联已恢复';
              _notify();
            }
          } else {
            status = '安装写入结果待确认，请恢复网络或重新登录';
            _notify();
          }
          return;
        }
        final receipt = createInstallationUuid();
        final next = <String, dynamic>{
          'scope': scope,
          'token': token.value,
          'channel': token.channelConfigurationId,
          'receipt': receipt,
          'pending': true,
        };
        // 在请求之前保存回执；进程退出或网络丢响应后不得盲目再写。
        await storage.write(jsonEncode(next));
        if (!_valid(s, generation)) return;
        PushBinding? result;
        try {
          result = await api.register(
            s,
            id,
            token,
            previous?.revision ?? '0',
            receipt,
          );
        } on AppRequestFailure catch (e) {
          if (e.unauthorized) rethrow; // 401不刷新或重放。
          if (e.status == null || e.status == 409 || e.status! >= 500) {
            if (_valid(s, generation)) result = await api.read(s, id);
          }
          if (!matches(result, receipt)) {
            // 确定拒绝允许下一次按最新摘要重新决策；网络未知保留待确认记录。
            if (e.status != null && e.status! < 500) await storage.write('{}');
            rethrow;
          }
        }
        if (!_valid(s, generation)) return;
        if (!matches(result, receipt)) {
          throw const FormatException('安装关联回执与当前会话不一致');
        }
        next['pending'] = false;
        next['confirmedAt'] = DateTime.now().toUtc().toIso8601String();
        await storage.write(jsonEncode(next));
        if (!_valid(s, generation)) return;
        binding = result;
        status = '安装关联已同步，实际通知由系统通道决定';
        _notify();
      } on AppRequestFailure catch (e) {
        if (_valid(s, generation)) {
          status = e.unauthorized ? '推送会话已失效，请重新登录' : '安装关联未确认，稍后重试';
          _notify();
        }
      } catch (_) {
        if (_valid(s, generation)) {
          status = '无法同步本机安装关联';
          _notify();
        }
      }
    });
    _tail = job;
    return job;
  }

  bool _valid(AppSession s, int generation) =>
      !_disposed &&
      generation == _generation &&
      identical(sessions.current, s) &&
      sessions.pendingCount == 0 &&
      !sessions.busy;
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    sessions.removeListener(_changed);
    super.dispose();
  }
}
