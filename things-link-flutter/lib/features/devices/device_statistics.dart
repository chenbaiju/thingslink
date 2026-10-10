import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

class DeviceStatistics {
  const DeviceStatistics(
    this.total,
    this.online,
    this.active24h,
    this.alarming,
    this.asOf,
  );
  final int total;
  final int online;
  final int active24h;
  final int alarming;
  final DateTime asOf;

  factory DeviceStatistics.parse(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('统计响应无效');
    final values = [
      'total',
      'online',
      'active24h',
      'alarming',
    ].map((key) => raw[key]).toList();
    final time = DateTime.tryParse(raw['asOf']?.toString() ?? '');
    if (values.any((v) => v is! int || v < 0) || time == null) {
      throw const FormatException('统计响应无效');
    }
    final counts = values.cast<int>();
    if (counts.skip(1).any((v) => v > counts.first)) {
      throw const FormatException('统计响应无效');
    }
    return DeviceStatistics(counts[0], counts[1], counts[2], counts[3], time);
  }
}

abstract interface class DeviceStatisticsApi {
  Future<DeviceStatistics> read(ProjectEntry entry, String accessToken);
}

class PlatformDeviceStatisticsApi implements DeviceStatisticsApi {
  PlatformDeviceStatisticsApi(this.transport);
  final AppTransport transport;
  @override
  Future<DeviceStatistics> read(ProjectEntry entry, String accessToken) async {
    try {
      return DeviceStatistics.parse(
        await transport.send(
          entry,
          '/api/v1/app/devices/statistics',
          accessToken: accessToken,
        ),
      );
    } on FormatException {
      throw const AppRequestFailure('设备统计响应无效，请联系管理员。');
    }
  }
}
