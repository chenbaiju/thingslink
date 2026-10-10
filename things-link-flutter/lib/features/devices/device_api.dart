import '../../core/network/app_http_client.dart';
import '../project_access/project_entry.dart';

class AppDevice {
  const AppDevice({
    required this.id,
    required this.deviceKey,
    required this.name,
    required this.status,
    this.description,
    this.location,
    this.deviceTypeName,
    this.lastOnlineAt,
    this.lastDataReportAt,
    this.createdAt,
  });
  final String id, deviceKey, name, status;
  final String? description, location, deviceTypeName;
  final DateTime? lastOnlineAt, lastDataReportAt, createdAt;
  String get statusLabel => switch (status) {
    'ONLINE' => '在线',
    'OFFLINE' => '离线',
    'INACTIVE' => '未激活',
    _ => '未知状态',
  };
  factory AppDevice.parse(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('设备响应无效');
    String requiredText(String key) {
      final value = raw[key];
      if (value is! String || value.isEmpty) {
        throw const FormatException('设备响应无效');
      }
      return value;
    }

    String? optionalText(String key) {
      final value = raw[key];
      if (value != null && value is! String) {
        throw const FormatException('设备响应无效');
      }
      return value as String?;
    }

    DateTime? time(String key) {
      final value = optionalText(key);
      if (value == null) return null;
      final parsed = DateTime.tryParse(value);
      if (parsed == null) throw const FormatException('设备时间无效');
      return parsed;
    }

    final id = requiredText('id');
    if (!RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$')
        .hasMatch(id)) {
      throw const FormatException('设备标识无效');
    }
    return AppDevice(
      id: id,
      deviceKey: requiredText('deviceKey'),
      name: requiredText('name'),
      status: requiredText('status'),
      description: optionalText('description'),
      location: optionalText('location'),
      deviceTypeName: optionalText('deviceTypeName'),
      lastOnlineAt: time('lastOnlineAt'),
      lastDataReportAt: time('lastDataReportAt'),
      createdAt: time('createdAt'),
    );
  }
}

class DevicePage {
  const DevicePage(this.items, this.nextCursor);
  final List<AppDevice> items;
  final String? nextCursor;
  factory DevicePage.parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['items'] is! List ||
        raw['hasMore'] is! bool) {
      throw const FormatException('设备分页无效');
    }
    final cursor = raw['nextCursor'];
    final hasMore = raw['hasMore'] as bool;
    if ((hasMore &&
            (cursor is! String || cursor.isEmpty || cursor.length > 512)) ||
        (!hasMore && cursor != null)) {
      throw const FormatException('设备游标无效');
    }
    final items = (raw['items'] as List)
        .map(AppDevice.parse)
        .toList(growable: false);
    if (items.length > 200 || (hasMore && items.isEmpty)) {
      throw const FormatException('设备分页无效');
    }
    return DevicePage(List.unmodifiable(items), cursor as String?);
  }
}

abstract interface class DeviceApi {
  Future<DevicePage> list(
    ProjectEntry entry,
    String token, {
    String query = '',
    String? status,
    String? cursor,
  });
  Future<AppDevice> detail(ProjectEntry entry, String token, String id);
}

class PlatformDeviceApi implements DeviceApi {
  PlatformDeviceApi(this.transport);
  final AppTransport transport;
  @override
  Future<DevicePage> list(
    ProjectEntry entry,
    String token, {
    String query = '',
    String? status,
    String? cursor,
  }) async {
    final path = Uri(
      path: '/api/v1/app/devices',
      queryParameters: {
        'limit': '20',
        if (query.isNotEmpty) 'q': query,
        'status': ?status,
        'cursor': ?cursor,
      },
    ).toString();
    try {
      return DevicePage.parse(
        await transport.send(entry, path, accessToken: token),
      );
    } on FormatException {
      throw const AppRequestFailure('设备列表响应无效，请联系管理员。');
    }
  }

  @override
  Future<AppDevice> detail(ProjectEntry entry, String token, String id) async {
    try {
      final device = AppDevice.parse(
        await transport.send(
          entry,
          '/api/v1/app/devices/${Uri.encodeComponent(id)}',
          accessToken: token,
        ),
      );
      if (device.id != id) throw const FormatException('设备不匹配');
      return device;
    } on FormatException {
      throw const AppRequestFailure('设备详情响应无效，请联系管理员。');
    }
  }
}

String deviceTime(DateTime? value, {String missing = '暂无记录'}) =>
    value == null ? missing : value.toLocal().toString().split('.').first;
