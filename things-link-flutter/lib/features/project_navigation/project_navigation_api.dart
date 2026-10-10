import '../../core/network/app_http_client.dart';
import '../../core/session/app_session.dart';
import '../project_access/project_entry.dart';

String navigationUuid(Object? value) {
  if (value is! String ||
      !RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
          .hasMatch(value)) {
    throw const FormatException('项目导航身份格式无效');
  }
  return value;
}

class NavigationIdentity {
  NavigationIdentity(this.backend, this.tenant, this.user);
  factory NavigationIdentity.parse(Map<String, dynamic> data) =>
      NavigationIdentity(
        navigationUuid(data['backendInstanceId']),
        navigationUuid(data['tenantId']),
        navigationUuid(data['appUserId']),
      );
  final String backend, tenant, user;
  bool matches(NavigationIdentity other) =>
      backend == other.backend && tenant == other.tenant && user == other.user;
}

class AuthorizedProject {
  AuthorizedProject(this.id, this.key, this.name, this.role);
  factory AuthorizedProject.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['projectKey'] is! String ||
        value['name'] is! String ||
        ![
          'APP_ADMIN',
          'MAINTAINER',
          'OPERATOR',
          'OBSERVER',
        ].contains(value['role'])) {
      throw const FormatException('项目列表格式无效');
    }
    final key = value['projectKey'] as String, name = value['name'] as String;
    ProjectEntry(baseUrl: 'https://validation.invalid', projectKey: key);
    if (name.length > 256) throw const FormatException('项目名称过长');
    return AuthorizedProject(
      navigationUuid(value['id']),
      key,
      name,
      value['role'] as String,
    );
  }
  final String id, key, name, role;
}

class AuthorizedProjectPage {
  AuthorizedProjectPage(
    this.identity,
    this.projectId,
    this.items,
    this.nextCursor,
  );
  factory AuthorizedProjectPage.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['items'] is! List ||
        (value['items'] as List).length > 100 ||
        (value['nextCursor'] != null &&
            (value['nextCursor'] is! String ||
                (value['nextCursor'] as String).isEmpty ||
                (value['nextCursor'] as String).length > 2048))) {
      throw const FormatException('项目列表格式无效');
    }
    final items = (value['items'] as List)
        .map(AuthorizedProject.parse)
        .toList();
    if (items.map((p) => p.id).toSet().length != items.length) {
      throw const FormatException('项目列表重复');
    }
    return AuthorizedProjectPage(
      NavigationIdentity.parse(value),
      navigationUuid(value['projectId']),
      items,
      value['nextCursor'] as String?,
    );
  }
  final NavigationIdentity identity;
  final String projectId;
  final List<AuthorizedProject> items;
  final String? nextCursor;
}

abstract interface class ProjectNavigationApi {
  Future<AuthorizedProjectPage> page(
    ProjectEntry entry,
    String token,
    String? cursor,
  );
  Future<AppSession> switchProject(
    AppSession source,
    NavigationIdentity identity,
    String projectId,
  );
}

class PlatformProjectNavigationApi implements ProjectNavigationApi {
  PlatformProjectNavigationApi(this.transport);
  final AppTransport transport;
  @override
  Future<AuthorizedProjectPage> page(
    ProjectEntry entry,
    String token,
    String? cursor,
  ) async {
    final path = Uri(
      path: '/api/v1/app/projects',
      queryParameters: {'limit': '20', 'cursor': ?cursor},
    ).toString();
    try {
      return AuthorizedProjectPage.parse(
        await transport.send(entry, path, accessToken: token),
      );
    } on FormatException {
      throw const AppRequestFailure('平台项目列表无效，请重试。');
    }
  }

  @override
  Future<AppSession> switchProject(
    AppSession source,
    NavigationIdentity identity,
    String projectId,
  ) async {
    navigationUuid(projectId);
    final response = await transport.send(
      source.entry,
      '/api/v1/app/auth/switch-project',
      method: 'POST',
      accessToken: source.accessToken,
      body: {'refreshToken': source.refreshToken, 'targetProjectId': projectId},
    );
    try {
      if (response is! Map<String, dynamic> ||
          !identity.matches(NavigationIdentity.parse(response)) ||
          response['projectId'] != projectId ||
          response['projectKey'] is! String ||
          response['projectName'] is! String ||
          response['session'] is! Map<String, dynamic>) {
        throw const FormatException();
      }
      final name = response['projectName'] as String;
      final entry = ProjectEntry(
        baseUrl: source.entry.baseUrl,
        projectKey: response['projectKey'] as String,
        label: name.length > 80 ? name.substring(0, 80) : name,
      );
      return AppSession.fromResponse(
        entry,
        source.username,
        response['session'] as Map<String, dynamic>,
      );
    } on FormatException {
      throw const AppRequestFailure('换签响应无效，请重新登录。');
    }
  }
}
