import '../../features/project_access/project_entry.dart';
import '../network/app_http_client.dart';
import 'session_controller.dart';

/// 身份或查询已改变，调用方静默丢弃结果，不显示成业务错误。
class StaleSessionRead implements Exception {}

/// 只用于幂等读取；一次401最多轮换重试一次，不重放写操作。
Future<T> readWithSession<T>(
  SessionController sessions,
  ProjectEntry entry,
  Future<T> Function(String token) read,
  bool Function() isCurrent,
) async {
  final revision = sessions.scopeRevision;
  bool valid() => revision == sessions.scopeRevision && isCurrent();
  Future<String> access() async {
    if (!valid()) throw StaleSessionRead();
    final token = await sessions.accessFor(entry);
    if (!valid() || token == null) throw StaleSessionRead();
    return token;
  }

  try {
    final result = await read(await access());
    if (!valid()) throw StaleSessionRead();
    return result;
  } on AppRequestFailure catch (failure) {
    if (!valid()) throw StaleSessionRead();
    if (!failure.unauthorized) rethrow;
    await sessions.refresh();
    try {
      final result = await read(await access());
      if (!valid()) throw StaleSessionRead();
      return result;
    } on AppRequestFailure catch (retryFailure) {
      if (!valid()) throw StaleSessionRead();
      if (retryFailure.unauthorized) await sessions.logout();
      rethrow;
    }
  }
}
