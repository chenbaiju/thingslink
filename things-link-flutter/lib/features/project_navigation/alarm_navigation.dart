import '../../core/session/app_session.dart';
import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import '../project_access/project_store.dart';
import 'project_navigation_api.dart';

/// 仅供应用内受控路由使用。厂商信封必须先经过TL-07-B安装代次门禁，不能直接作为此对象传入。
class AlarmNavigationRequest {
  AlarmNavigationRequest({
    required this.identity,
    required String projectId,
    required String alarmId,
  }) : projectId = navigationUuid(projectId),
       alarmId = navigationUuid(alarmId);
  final NavigationIdentity identity;
  final String projectId, alarmId;
}

class AlarmNavigationResult {
  AlarmNavigationResult(this.alarmId, this.scopeRevision);
  final String alarmId;
  final int scopeRevision;
}

/// 查询可信当前身份后换签；不从载荷导入URL或把名称/账号文本当身份。
Future<AlarmNavigationResult> resolveAlarmNavigation(
  AlarmNavigationRequest request,
  SessionController sessions,
  ProjectStore store,
  ProjectNavigationApi api,
  bool Function() isCurrent,
) async {
  final entry = sessions.current?.entry;
  if (entry == null) throw const AppRequestFailure('请登录后查看告警。');
  final page = await readWithSession(
    sessions,
    entry,
    (token) => api.page(entry, token, null),
    isCurrent,
  );
  if (!page.identity.matches(request.identity)) {
    throw const AppRequestFailure('此告警不属于当前平台或账号，请选择对应入口登录。');
  }
  if (!isCurrent()) throw StaleSessionRead();
  if (page.projectId != request.projectId) {
    AppSession? issued;
    await sessions.switchProject((source) async {
      if (!isCurrent()) throw StaleSessionRead();
      issued = await api.switchProject(
        source,
        request.identity,
        request.projectId,
      );
      return issued!;
    }, store.save);
    if (sessions.error != null ||
        sessions.current == null ||
        !identical(sessions.current, issued)) {
      throw AppRequestFailure(sessions.error ?? '项目切换未完成，请重新登录。');
    }
  }
  if (!isCurrent()) throw StaleSessionRead();
  return AlarmNavigationResult(request.alarmId, sessions.scopeRevision);
}
