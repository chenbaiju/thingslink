import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

import '../session/session_storage.dart';

abstract interface class InstallationIdentity {
  Future<String> get id;
}

/// 容器偏好随卸载移除；Keychain可能保留，因此每次安装使用新的安全存储命名空间。
class PreferencesInstallationIdentity implements InstallationIdentity {
  PreferencesInstallationIdentity({
    this.preferenceKey = 'thingsx.installation.v1',
    this.legacyStorage,
  });
  final String preferenceKey;
  final SessionStorage? legacyStorage;
  late final SharedPreferencesAsync _preferences = SharedPreferencesAsync();
  Future<String>? _id;
  @override
  Future<String> get id => _id ??= _load();
  Future<String> _load() async {
    final key = preferenceKey;
    final saved = await _preferences.getString(key);
    if (saved != null) {
      if (!RegExp(
        r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
      ).hasMatch(saved)) {
        throw const FormatException('安装标识损坏');
      }
      return saved;
    }
    final generated = createInstallationUuid();
    // 首次升级也要求重新登录：没有旧安装标记，无法可靠区分升级和卸载重装。
    await (legacyStorage ?? SecureSessionStorage()).write(
      const SessionSnapshot().encode(),
    );
    await _preferences.setString(key, generated);
    return generated;
  }
}

class InstallationSessionStorage implements SessionStorage {
  InstallationSessionStorage(
    this.installation, {
    this.namespace = 'session.v2',
  });
  final String namespace;
  final InstallationIdentity installation;
  Future<SecureSessionStorage> get _storage async =>
      SecureSessionStorage(key: 'thingsx.$namespace.${await installation.id}');
  @override
  Future<String?> read() async => (await _storage).read();
  @override
  Future<void> write(String value) async => (await _storage).write(value);
}

String createInstallationUuid() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}
