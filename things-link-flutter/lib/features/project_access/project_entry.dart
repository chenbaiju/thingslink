import 'dart:convert';

/// 项目入口只标识目标，不承载任何登录凭据或访问权限。
class ProjectEntry {
  ProjectEntry._(this.baseUrl, this.projectKey, this.label);

  /// 与Flutter的Debug编译模式一致，Profile/Release不能启用本机HTTP例外。
  static const localHttpDebugEnabled =
      !bool.fromEnvironment('dart.vm.product') &&
      !bool.fromEnvironment('dart.vm.profile');
  static const localDebugBaseUrl = 'http://127.0.0.1:8080';

  factory ProjectEntry({
    required String baseUrl,
    required String projectKey,
    String label = '',
  }) {
    final address = baseUrl.trim();
    final key = projectKey.trim();
    final name = label.trim();
    final uri = Uri.tryParse(address);
    final localDebug =
        localHttpDebugEnabled &&
        (address == localDebugBaseUrl || address == '$localDebugBaseUrl/');
    if (address.length > 2048 ||
        uri == null ||
        (uri.scheme != 'https' && !localDebug) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path.isNotEmpty && uri.path != '/') ||
        uri.port < 1 ||
        uri.port > 65535 ||
        RegExp(r'[\s\\]').hasMatch(address)) {
      throw const FormatException(
        localHttpDebugEnabled
            ? '请输入 HTTPS 平台根地址；本机调试仅支持 http://127.0.0.1:8080'
            : '请输入 HTTPS 平台地址，不包含路径、账号或查询参数',
      );
    }
    if (key.isEmpty ||
        key.length > 64 ||
        RegExp(r'[\x00-\x20\x7f]').hasMatch(key)) {
      throw const FormatException('项目标识应为 1～64 个字符，不包含空白');
    }
    if (name.length > 80 || RegExp(r'[\x00-\x1f\x7f]').hasMatch(name)) {
      throw const FormatException('项目名称最多 80 个字符，不包含控制字符');
    }
    return ProjectEntry._(uri.replace(path: '').toString(), key, name);
  }

  final String baseUrl;
  final String projectKey;
  final String label;
  String get id =>
      base64Url.encode(utf8.encode(jsonEncode([baseUrl, projectKey])));
  String get displayName => label.isEmpty ? projectKey : label;

  Map<String, Object> toJson() => {
    'version': 1,
    'baseUrl': baseUrl,
    'projectKey': projectKey,
    'label': label,
  };

  static ProjectEntry fromJson(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['version'] is! int ||
        value['version'] != 1 ||
        value['baseUrl'] is! String ||
        value['projectKey'] is! String ||
        (value.containsKey('label') && value['label'] is! String) ||
        value.keys.any(
          (k) => !{'version', 'baseUrl', 'projectKey', 'label'}.contains(k),
        )) {
      throw const FormatException('项目接入信息格式或版本不支持');
    }
    return ProjectEntry(
      baseUrl: value['baseUrl'] as String,
      projectKey: value['projectKey'] as String,
      label: value['label'] as String? ?? '',
    );
  }

  static ProjectEntry parse(String input) {
    if (utf8.encode(input).length > 4096) {
      throw const FormatException('项目接入信息过长');
    }
    return fromJson(jsonDecode(input));
  }
}
