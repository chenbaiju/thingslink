import 'package:flutter/foundation.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../project_access/project_entry.dart';
import 'account_api.dart';

enum PasswordOutcome { changed, uncertain, expired, stale }

/// 写请求只发一次；不确定结果停止本机会话，等待用户重新登录确认。
class PasswordController extends ChangeNotifier {
  PasswordController(this.sessions, this.api)
    : entry = sessions.current?.entry,
      username = sessions.current?.username,
      scope = sessions.scopeRevision;
  final SessionController sessions;
  final AccountApi api;
  final ProjectEntry? entry;
  final String? username;
  final int scope;
  bool busy = false, _disposed = false;
  String? error;
  PasswordOutcome? outcome;
  bool get sameSession =>
      scope == sessions.scopeRevision &&
      sessions.current?.entry.id == entry?.id &&
      sessions.current?.username == username;

  Future<void> submit(String oldPassword, String newPassword) async {
    if (busy || outcome != null || _disposed) return;
    error = oldPassword.isEmpty || oldPassword.length > 128
        ? '请填写有效的原密码。'
        : validateNewPassword(newPassword);
    if (error != null) {
      notifyListeners();
      return;
    }
    final project = entry;
    if (!sameSession || project == null) {
      outcome = PasswordOutcome.stale;
      notifyListeners();
      return;
    }
    busy = true;
    notifyListeners();
    try {
      final token = await sessions.accessFor(project);
      if (!sameSession || token == null) {
        outcome = PasswordOutcome.expired;
        return;
      }
      await api.changePassword(project, token, oldPassword, newPassword);
      if (!sameSession) {
        outcome = PasswordOutcome.stale;
        return;
      }
      outcome = PasswordOutcome.changed;
      await sessions.logout();
    } on AppRequestFailure catch (failure) {
      if (!sameSession) {
        outcome = PasswordOutcome.stale;
        return;
      }
      if (failure.unauthorized) {
        outcome = PasswordOutcome.expired;
        await sessions.logout();
      } else if (failure.status == null || failure.status! >= 500) {
        outcome = PasswordOutcome.uncertain;
        await sessions.logout();
      } else {
        error = failure.message;
      }
    } catch (_) {
      if (sameSession) {
        outcome = PasswordOutcome.uncertain;
        await sessions.logout();
      } else {
        outcome = PasswordOutcome.stale;
      }
    } finally {
      busy = false;
      if (!_disposed) notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
