import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../features/project_access/project_entry.dart';
import 'app_session.dart';

abstract interface class SessionStorage {
  Future<String?> read();
  Future<void> write(String value);
}

class SecureSessionStorage implements SessionStorage {
  SecureSessionStorage({this.key = 'thingsx.session.v1'});
  final String key;
  static const _android = MethodChannel('thingsx/session_storage');
  static const _storage = FlutterSecureStorage(
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.unlocked_this_device,
    ),
  );
  @override
  Future<String?> read() => Platform.isAndroid
      ? _android.invokeMethod<String>('read', {'key': key})
      : _storage.read(key: key);
  @override
  Future<void> write(String value) => Platform.isAndroid
      ? _android.invokeMethod<void>('write', {'key': key, 'value': value})
      : _storage.write(key: key, value: value);
}

class PendingRevocation {
  PendingRevocation(this.entry, this.refreshToken);
  final ProjectEntry entry;
  final String refreshToken;
  Map<String, Object> toJson() => {
    'entry': entry.toJson(),
    'refreshToken': refreshToken,
  };
  factory PendingRevocation.fromJson(Object? data) {
    if (data is! Map<String, dynamic> ||
        data['refreshToken'] is! String ||
        (data['refreshToken'] as String).isEmpty ||
        (data['refreshToken'] as String).length > 4096) {
      throw const FormatException('待撤销记录无效');
    }
    return PendingRevocation(
      ProjectEntry.fromJson(data['entry']),
      data['refreshToken'] as String,
    );
  }
  @override
  String toString() => 'PendingRevocation[redacted]';
}

class SessionSnapshot {
  const SessionSnapshot({
    this.active,
    this.rotating = false,
    this.pending = const [],
  });
  final AppSession? active;
  final bool rotating;
  final List<PendingRevocation> pending;
  String encode() => jsonEncode({
    'version': 1,
    'active': active?.toJson(),
    'rotating': rotating,
    'pending': pending.map((e) => e.toJson()).toList(),
  });
  static SessionSnapshot decode(String? raw) {
    if (raw == null) return const SessionSnapshot();
    if (raw.length > 1048576) throw const FormatException('会话记录过大');
    final data = jsonDecode(raw);
    if (data is! Map<String, dynamic> ||
        data['version'] is! int ||
        data['version'] != 1 ||
        data['rotating'] is! bool ||
        data['pending'] is! List ||
        (data['pending'] as List).length > 50) {
      throw const FormatException('会话记录无效');
    }
    final active = data['active'] == null
        ? null
        : AppSession.fromStored(data['active']);
    if (data['rotating'] == true && active == null) {
      throw const FormatException('在途会话缺失');
    }
    return SessionSnapshot(
      active: active,
      rotating: data['rotating'] as bool,
      pending: (data['pending'] as List)
          .map(PendingRevocation.fromJson)
          .toList(),
    );
  }
}
