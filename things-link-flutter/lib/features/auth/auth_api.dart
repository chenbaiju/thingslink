import '../../core/network/app_http_client.dart';
import '../../core/session/app_session.dart';
import '../project_access/project_entry.dart';

abstract interface class AuthApi {
  Future<AppSession> login(
    ProjectEntry entry,
    String username,
    String password,
  );
  Future<AppSession> refresh(AppSession previous);
  Future<void> logout(ProjectEntry entry, String refreshToken);
}

class PlatformAuthApi implements AuthApi {
  PlatformAuthApi(this.transport);
  final AppTransport transport;
  Future<AppSession> _issue(
    ProjectEntry entry,
    String username,
    String action,
    Map<String, Object?> body,
  ) async {
    final response = await transport.send(
      entry,
      '/api/v1/app/auth/$action',
      method: 'POST',
      body: body,
    );
    if (response is! Map<String, dynamic>) {
      throw const AppRequestFailure('平台会话响应无效，请重新登录。');
    }
    try {
      return AppSession.fromResponse(entry, username, response);
    } on FormatException {
      throw const AppRequestFailure('平台会话响应无效，请重新登录。');
    }
  }

  @override
  Future<AppSession> login(
    ProjectEntry entry,
    String username,
    String password,
  ) => _issue(entry, username, 'login', {
    'projectKey': entry.projectKey,
    'username': username,
    'password': password,
  });
  @override
  Future<AppSession> refresh(AppSession previous) => _issue(
    previous.entry,
    previous.username,
    'refresh',
    {'refreshToken': previous.refreshToken},
  );
  @override
  Future<void> logout(ProjectEntry entry, String refreshToken) async {
    await transport.send(
      entry,
      '/api/v1/app/auth/logout',
      method: 'POST',
      body: {'refreshToken': refreshToken},
      expectedStatus: 204,
    );
  }
}
