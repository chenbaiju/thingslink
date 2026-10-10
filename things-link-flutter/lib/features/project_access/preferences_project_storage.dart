import 'package:shared_preferences/shared_preferences.dart';

import 'project_store.dart';

/// 仅存放可重新获取的非敏感入口；密码和令牌禁止写入偏好存储。
class PreferencesProjectStorage implements ProjectStorage {
  final SharedPreferencesAsync _preferences = SharedPreferencesAsync();
  static const _key = 'thingsx.project_entries.v1';
  @override
  Future<String?> read() => _preferences.getString(_key);
  @override
  Future<void> write(String value) => _preferences.setString(_key, value);
}
