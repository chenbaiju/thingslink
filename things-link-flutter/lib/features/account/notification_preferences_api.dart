import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

class NotificationPreferences {
  const NotificationPreferences({
    required this.appPushEnabled,
    required this.revision,
    required this.editable,
  });
  final bool appPushEnabled, editable;
  final String revision;
  factory NotificationPreferences.parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['appPushEnabled'] is! bool ||
        raw['editable'] is! bool ||
        raw['revision'] is! String) {
      throw const FormatException('偏好响应无效');
    }
    final revision = raw['revision'] as String;
    if (!RegExp(r'^(0|[1-9][0-9]{0,18})$').hasMatch(revision) ||
        BigInt.parse(revision) > BigInt.parse('9223372036854775807')) {
      throw const FormatException('偏好版本无效');
    }
    return NotificationPreferences(
      appPushEnabled: raw['appPushEnabled'] as bool,
      revision: revision,
      editable: raw['editable'] as bool,
    );
  }
}

abstract interface class NotificationPreferencesApi {
  Future<NotificationPreferences> read(ProjectEntry entry, String token);
  Future<NotificationPreferences> update(
    ProjectEntry entry,
    String token,
    bool enabled,
    String revision,
  );
}

class PlatformNotificationPreferencesApi implements NotificationPreferencesApi {
  PlatformNotificationPreferencesApi(this.transport);
  final AppTransport transport;
  static const _path = '/api/v1/app/account/notification-preferences';
  @override
  Future<NotificationPreferences> read(ProjectEntry entry, String token) async {
    try {
      return NotificationPreferences.parse(
        await transport.send(entry, _path, accessToken: token),
      );
    } on FormatException {
      throw const AppRequestFailure('通知设置响应无效，请重试。');
    }
  }

  @override
  Future<NotificationPreferences> update(
    ProjectEntry entry,
    String token,
    bool enabled,
    String revision,
  ) async {
    try {
      final result = NotificationPreferences.parse(
        await transport.send(
          entry,
          _path,
          method: 'PUT',
          accessToken: token,
          body: {'appPushEnabled': enabled, 'expectedRevision': revision},
        ),
      );
      if (result.appPushEnabled != enabled ||
          BigInt.parse(result.revision) < BigInt.parse(revision)) {
        throw const FormatException('保存回执不一致');
      }
      return result;
    } on FormatException {
      throw const AppRequestFailure('保存回执未确认，请重新读取通知设置。');
    }
  }
}
