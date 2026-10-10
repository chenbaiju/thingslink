import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

const alarmSeverities = {
  'CRITICAL': '紧急',
  'MAJOR': '严重',
  'MINOR': '次要',
  'WARNING': '警告',
  'INFO': '提示',
};
const alarmConditions = {'ACTIVE': '未解除', 'CLEARED': '已解除'};

class AlarmFilter {
  const AlarmFilter({
    this.deviceId,
    this.deviceLabel,
    this.severity,
    this.conditionState,
    this.from,
    this.to,
  });
  final String? deviceId, severity, conditionState;
  final String? deviceLabel;
  final DateTime? from, to;
  Map<String, String> get parameters => {
    'deviceId': ?deviceId,
    'severity': ?severity,
    'conditionState': ?conditionState,
    if (from != null) 'from': from!.toUtc().toIso8601String(),
    if (to != null) 'to': to!.toUtc().toIso8601String(),
  };
}

class AppAlarm {
  const AppAlarm({
    required this.id,
    required this.deviceId,
    required this.deviceName,
    required this.deviceKey,
    required this.alarmType,
    required this.severity,
    required this.conditionState,
    required this.ackState,
    required this.firstConditionAt,
    required this.lastReceivedAt,
    this.activatedAt,
    this.clearedAt,
    this.acknowledgedAt,
  });
  final String id,
      deviceId,
      deviceName,
      deviceKey,
      alarmType,
      severity,
      conditionState,
      ackState;
  final DateTime firstConditionAt, lastReceivedAt;
  final DateTime? activatedAt, clearedAt, acknowledgedAt;
  String get severityLabel => alarmSeverities[severity] ?? '未知等级';
  String get conditionLabel => alarmConditions[conditionState] ?? '未知状态';
  String get ackLabel => switch (ackState) {
    'ACKNOWLEDGED' => '已确认',
    'UNACKNOWLEDGED' => '未确认',
    _ => '确认状态未知',
  };
  factory AppAlarm.parse(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('告警响应无效');
    String text(String key) {
      final value = raw[key];
      if (value is! String || value.isEmpty) {
        throw const FormatException('告警字段无效');
      }
      return value;
    }

    String id(String key) {
      final value = text(key);
      if (!RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$')
          .hasMatch(value)) {
        throw const FormatException('告警标识无效');
      }
      return value;
    }

    DateTime? time(String key, {bool required = false}) {
      final value = raw[key];
      if (value == null && !required) return null;
      if (value is! String) throw const FormatException('告警时间无效');
      final result = DateTime.tryParse(value);
      if (result == null) throw const FormatException('告警时间无效');
      return result;
    }

    return AppAlarm(
      id: id('id'),
      deviceId: id('deviceId'),
      deviceName: text('deviceName'),
      deviceKey: text('deviceKey'),
      alarmType: text('alarmType'),
      severity: text('severity'),
      conditionState: text('conditionState'),
      ackState: text('ackState'),
      firstConditionAt: time('firstConditionAt', required: true)!,
      lastReceivedAt: time('lastReceivedAt', required: true)!,
      activatedAt: time('activatedAt'),
      clearedAt: time('clearedAt'),
      acknowledgedAt: time('acknowledgedAt'),
    );
  }
}

class AlarmPage {
  const AlarmPage(this.items, this.nextCursor);
  final List<AppAlarm> items;
  final String? nextCursor;
  factory AlarmPage.parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['items'] is! List ||
        raw['hasMore'] is! bool) {
      throw const FormatException('告警分页无效');
    }
    final cursor = raw['nextCursor'];
    final more = raw['hasMore'] as bool;
    if ((more &&
            (cursor is! String || cursor.isEmpty || cursor.length > 2048)) ||
        (!more && cursor != null)) {
      throw const FormatException('告警游标无效');
    }
    final items = (raw['items'] as List)
        .map(AppAlarm.parse)
        .toList(growable: false);
    if (items.length > 50 || (more && items.isEmpty)) {
      throw const FormatException('告警分页无效');
    }
    return AlarmPage(List.unmodifiable(items), cursor as String?);
  }
}

abstract interface class AlarmApi {
  Future<AlarmPage> list(
    ProjectEntry entry,
    String token, {
    AlarmFilter filter = const AlarmFilter(),
    String? cursor,
  });
  Future<AppAlarm> detail(ProjectEntry entry, String token, String id);
}

class PlatformAlarmApi implements AlarmApi {
  PlatformAlarmApi(this.transport);
  final AppTransport transport;
  @override
  Future<AlarmPage> list(
    ProjectEntry entry,
    String token, {
    AlarmFilter filter = const AlarmFilter(),
    String? cursor,
  }) async {
    try {
      return AlarmPage.parse(
        await transport.send(
          entry,
          Uri(
            path: '/api/v1/app/alarms',
            queryParameters: {
              'limit': '20',
              ...filter.parameters,
              'cursor': ?cursor,
            },
          ).toString(),
          accessToken: token,
        ),
      );
    } on FormatException {
      throw const AppRequestFailure('告警列表响应无效，请联系管理员。');
    }
  }

  @override
  Future<AppAlarm> detail(ProjectEntry entry, String token, String id) async {
    try {
      final alarm = AppAlarm.parse(
        await transport.send(
          entry,
          '/api/v1/app/alarms/${Uri.encodeComponent(id)}',
          accessToken: token,
        ),
      );
      if (alarm.id != id) throw const FormatException('告警不匹配');
      return alarm;
    } on FormatException {
      throw const AppRequestFailure('告警详情响应无效，请联系管理员。');
    }
  }
}
