import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../features/project_access/project_entry.dart';

class AppRequestFailure implements Exception {
  const AppRequestFailure(this.message, {this.status});
  final String message;
  final int? status;
  bool get unauthorized => status == 401;
  @override
  String toString() => message;
}

abstract interface class AppTransport {
  Future<Object?> send(
    ProjectEntry entry,
    String path, {
    String method = 'GET',
    Map<String, Object?>? body,
    String? accessToken,
    int expectedStatus = 200,
  });
}

/// 固定同源App路径，不跟随重定向，不覆盖系统证书校验。
class AppHttpClient implements AppTransport {
  @override
  Future<Object?> send(
    ProjectEntry entry,
    String path, {
    String method = 'GET',
    Map<String, Object?>? body,
    String? accessToken,
    int expectedStatus = 200,
  }) async {
    final relative = Uri.parse(path);
    if (relative.hasScheme ||
        relative.hasAuthority ||
        relative.hasFragment ||
        !relative.path.startsWith('/api/v1/app/') ||
        relative.pathSegments.any((s) => s == '..' || s == '.')) {
      throw ArgumentError('仅允许同源App接口');
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      return await (() async {
        final request = await client.openUrl(
          method,
          Uri.parse(entry.baseUrl).resolveUri(relative),
        );
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json');
        if (accessToken != null) {
          request.headers.set(
            HttpHeaders.authorizationHeader,
            'Bearer $accessToken',
          );
        }
        if (body != null) {
          request.headers.contentType = ContentType.json;
          request.write(jsonEncode(body));
        }
        final response = await request.close();
        if (response.statusCode != expectedStatus) {
          final status = response.statusCode;
          throw AppRequestFailure(switch (status) {
            401 => '登录凭据无效，请检查项目、账号和密码或重新登录。',
            403 => '当前账号无法访问，请联系项目管理员。',
            429 => '操作过于频繁，请稍后再试。',
            >= 300 && < 400 => '平台返回了重定向，请核对管理员提供的地址。',
            >= 500 => '平台暂时不可用，请稍后重试。',
            _ => '请求未完成，请检查输入后重试。',
          }, status: status);
        }
        if (expectedStatus == 204) return null;
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 1048576) {
            throw const AppRequestFailure('平台响应过大，请联系管理员。');
          }
          bytes.addAll(chunk);
        }
        return jsonDecode(utf8.decode(bytes));
      })().timeout(const Duration(seconds: 15));
    } on AppRequestFailure {
      rethrow;
    } on HandshakeException {
      throw const AppRequestFailure('无法建立安全连接，请检查平台地址与证书。');
    } on TimeoutException {
      throw const AppRequestFailure('连接超时，请检查网络后重试。');
    } on IOException {
      throw const AppRequestFailure('无法连接平台，请检查网络与平台地址。');
    } on FormatException {
      throw const AppRequestFailure('平台响应格式无效，请联系管理员。');
    } finally {
      client.close(force: true);
    }
  }
}
