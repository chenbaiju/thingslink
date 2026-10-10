import 'dart:convert';

import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

class AppAccount {
  const AppAccount({
    required this.id,
    required this.username,
    required this.createdAt,
    required this.projectRole,
    required this.passwordChangeAllowed,
    this.displayName,
  });
  final String id, username, projectRole;
  final String? displayName;
  final DateTime createdAt;
  final bool passwordChangeAllowed;
  String get roleLabel => switch (projectRole) {
    'APP_ADMIN' => '应用管理员',
    'MAINTAINER' => '维护者',
    'OPERATOR' => '操作员',
    'OBSERVER' => '观察者',
    _ => '未知角色',
  };
  factory AppAccount.parse(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('账户响应无效');
    String text(String key) {
      final value = raw[key];
      if (value is! String || value.isEmpty) {
        throw const FormatException('账户字段无效');
      }
      return value;
    }

    final id = text('id');
    final created = DateTime.tryParse(text('createdAt'));
    if (!RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$')
            .hasMatch(id) ||
        created == null ||
        raw['passwordChangeAllowed'] is! bool ||
        (raw['displayName'] != null && raw['displayName'] is! String)) {
      throw const FormatException('账户响应无效');
    }
    return AppAccount(
      id: id,
      username: text('username'),
      createdAt: created,
      projectRole: text('projectRole'),
      passwordChangeAllowed: raw['passwordChangeAllowed'] as bool,
      displayName: raw['displayName'] as String?,
    );
  }
}

abstract interface class AccountApi {
  Future<AppAccount> read(ProjectEntry entry, String token);
  Future<void> changePassword(
    ProjectEntry entry,
    String token,
    String oldPassword,
    String newPassword,
  );
}

class PlatformAccountApi implements AccountApi {
  PlatformAccountApi(this.transport);
  final AppTransport transport;
  @override
  Future<AppAccount> read(ProjectEntry entry, String token) async {
    try {
      return AppAccount.parse(
        await transport.send(entry, '/api/v1/app/account', accessToken: token),
      );
    } on FormatException {
      throw const AppRequestFailure('账户资料响应无效，请联系管理员。');
    }
  }

  @override
  Future<void> changePassword(
    ProjectEntry entry,
    String token,
    String oldPassword,
    String newPassword,
  ) async {
    try {
      await transport.send(
        entry,
        '/api/v1/app/auth/password',
        method: 'POST',
        body: {'oldPassword': oldPassword, 'newPassword': newPassword},
        accessToken: token,
        expectedStatus: 204,
      );
    } on AppRequestFailure catch (failure) {
      throw AppRequestFailure(switch (failure.status) {
        400 => '原密码不正确或新密码不符合要求，请核对后重新填写。',
        403 => '当前项目暂不允许修改密码。',
        _ => failure.message,
      }, status: failure.status);
    }
  }
}

String? validateNewPassword(String value) {
  if (value.trim().isEmpty || value.length < 8) return '新密码至少需要8位。';
  if (value.length > 128 || utf8.encode(value).length > 72) {
    return '新密码过长，请适当缩短。';
  }
  return null;
}
