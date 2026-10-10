import '../../features/project_access/project_entry.dart';

/// 会话只属于签发时的入口；所有字段都不得进入日志。
class AppSession {
  AppSession({
    required this.entry,
    required this.username,
    required this.accessToken,
    required this.refreshToken,
    required this.accessExpiresAt,
    required this.refreshExpiresAt,
    this.identity,
  });
  final SessionIdentity? identity;
  final ProjectEntry entry;
  final String username;
  final String accessToken;
  final String refreshToken;
  final DateTime accessExpiresAt;
  final DateTime refreshExpiresAt;

  factory AppSession.fromResponse(
    ProjectEntry entry,
    String username,
    Map<String, dynamic> data, {
    bool allowExpired = false,
  }) {
    final access = data['accessToken'];
    final refresh = data['refreshToken'];
    final accessExpiry = DateTime.tryParse(
      data['accessExpiresAt']?.toString() ?? '',
    );
    final refreshExpiry = DateTime.tryParse(
      data['refreshExpiresAt']?.toString() ?? '',
    );
    if (access is! String ||
        access.isEmpty ||
        access.length > 16384 ||
        RegExp(r'\s').hasMatch(access) ||
        refresh is! String ||
        refresh.isEmpty ||
        refresh.length > 4096 ||
        RegExp(r'\s').hasMatch(refresh) ||
        accessExpiry == null ||
        refreshExpiry == null ||
        (!allowExpired && !accessExpiry.isAfter(DateTime.now())) ||
        (!allowExpired && !refreshExpiry.isAfter(DateTime.now()))) {
      throw const FormatException('会话响应无效，请重新登录');
    }
    return AppSession(
      identity: data['identity'] == null
          ? null
          : SessionIdentity.fromJson(data['identity']),
      entry: entry,
      username: username,
      accessToken: access,
      refreshToken: refresh,
      accessExpiresAt: accessExpiry,
      refreshExpiresAt: refreshExpiry,
    );
  }

  factory AppSession.fromStored(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['username'] is! String ||
        (value['username'] as String).isEmpty ||
        (value['username'] as String).length > 64) {
      throw const FormatException('会话记录无效');
    }
    return AppSession.fromResponse(
      ProjectEntry.fromJson(value['entry']),
      value['username'] as String,
      value,
      allowExpired: true,
    );
  }

  Map<String, Object> toJson() => {
    if (identity != null) 'identity': identity!.toJson(),
    'entry': entry.toJson(),
    'username': username,
    'accessToken': accessToken,
    'refreshToken': refreshToken,
    'accessExpiresAt': accessExpiresAt.toUtc().toIso8601String(),
    'refreshExpiresAt': refreshExpiresAt.toUtc().toIso8601String(),
  };
  @override
  String toString() => 'AppSession[redacted]';
}

/// 只消费认证响应中的服务器身份，不解码JWT或从用户输入推导归属。
class SessionIdentity {
  SessionIdentity(
    this.backendInstanceId,
    this.tenantId,
    this.appUserId,
    this.projectId,
    this.sessionId,
    this.sessionGroupId,
  );
  final String? backendInstanceId;
  final String tenantId, appUserId, projectId, sessionId, sessionGroupId;
  static final uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  factory SessionIdentity.fromJson(Object? data) {
    if (data is! Map<String, dynamic>) throw const FormatException('会话身份无效');
    String field(String key) {
      final value = data[key];
      if (value is! String || !uuid.hasMatch(value)) {
        throw const FormatException('会话身份无效');
      }
      return value.toLowerCase();
    }

    return SessionIdentity(
      data['backendInstanceId'] == null ? null : field('backendInstanceId'),
      field('tenantId'),
      field('appUserId'),
      field('projectId'),
      field('sessionId'),
      field('sessionGroupId'),
    );
  }
  String get bindingScope =>
      '$backendInstanceId/$tenantId/$appUserId/$sessionGroupId';
  Map<String, Object?> toJson() => {
    'backendInstanceId': backendInstanceId,
    'tenantId': tenantId,
    'appUserId': appUserId,
    'projectId': projectId,
    'sessionId': sessionId,
    'sessionGroupId': sessionGroupId,
  };
}
