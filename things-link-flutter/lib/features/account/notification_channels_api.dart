import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

class NotificationChannels {
  const NotificationChannels({
    this.voiceNumber,
    this.smsNumber,
    required this.voiceAvailable,
    required this.smsAvailable,
  });
  final String? voiceNumber, smsNumber;
  final bool voiceAvailable, smsAvailable;
  factory NotificationChannels.parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['voiceAvailable'] is! bool ||
        raw['smsAvailable'] is! bool) {
      throw const FormatException('渠道响应无效');
    }
    String? number(String field) {
      final value = raw[field];
      if (!raw.containsKey(field) ||
          (value != null &&
              (value is! String ||
                  !RegExp(r'^\+[1-9][0-9]{6,14}$').hasMatch(value)))) {
        throw const FormatException('号码响应无效');
      }
      return value as String?;
    }

    return NotificationChannels(
      voiceNumber: number('voiceNumber'),
      smsNumber: number('smsNumber'),
      voiceAvailable: raw['voiceAvailable'] as bool,
      smsAvailable: raw['smsAvailable'] as bool,
    );
  }
}

abstract interface class NotificationChannelsApi {
  Future<NotificationChannels> read(ProjectEntry entry, String token);
}

class PlatformNotificationChannelsApi implements NotificationChannelsApi {
  PlatformNotificationChannelsApi(this.transport);
  final AppTransport transport;
  @override
  Future<NotificationChannels> read(ProjectEntry entry, String token) async {
    try {
      return NotificationChannels.parse(
        await transport.send(
          entry,
          '/api/v1/app/account/notification-channels',
          accessToken: token,
        ),
      );
    } on FormatException {
      throw const AppRequestFailure('通知渠道响应无效，请联系管理员。');
    }
  }
}
