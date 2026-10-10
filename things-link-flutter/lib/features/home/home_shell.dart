import '../project_navigation/project_navigation_page.dart';
import '../project_navigation/project_navigation_api.dart';
import '../project_navigation/alarm_navigation.dart';
import '../alarms/alarm_detail_page.dart';
import '../../core/session/session_read.dart';

import 'dart:async';

import '../account/notification_channels_api.dart';
import '../account/notification_preferences_api.dart';
import '../account/account_page.dart';
import '../account/account_api.dart';
import '../alarms/alarm_list_controller.dart';
import '../alarms/alarm_list_panel.dart';
import '../alarms/alarm_api.dart';

import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import '../../core/network/app_http_client.dart';
import '../devices/device_statistics.dart';
import '../devices/device_api.dart';
import '../devices/device_list_controller.dart';
import '../devices/device_list_panel.dart';
import '../devices/device_statistics_controller.dart';
import '../devices/device_statistics_panel.dart';
import '../../shared/content_state.dart';
import '../../shared/foreground_refresh.dart';
import '../about/about_page.dart';
import '../auth/login_page.dart';
import '../project_access/project_access_page.dart';
import '../project_access/project_store.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({
    super.key,
    required this.store,
    required this.sessions,
    this.statisticsApi,
    this.deviceApi,
    this.alarmApi,
    this.accountApi,
    this.notificationPreferencesApi,
    this.notificationChannelsApi,
    this.projectNavigationApi,
    this.alarmNavigation,
  });
  final ProjectStore store;
  final SessionController sessions;
  final DeviceStatisticsApi? statisticsApi;
  final DeviceApi? deviceApi;
  final AlarmApi? alarmApi;
  final AccountApi? accountApi;
  final NotificationPreferencesApi? notificationPreferencesApi;
  final NotificationChannelsApi? notificationChannelsApi;
  final ProjectNavigationApi? projectNavigationApi;
  final ValueNotifier<AlarmNavigationRequest?>? alarmNavigation;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  int _navigationEpoch = 0;
  bool _hadAuthenticatedSession = false;
  bool _navigating = false;
  String? _navigationError;
  late final ProjectNavigationApi _projectNavigation =
      widget.projectNavigationApi ??
      PlatformProjectNavigationApi(AppHttpClient());
  bool _visible = false;
  static const _titles = ['我的设备', '告警历史', '我的'];
  late final _changes = Listenable.merge([widget.store, widget.sessions]);
  late final DeviceStatisticsController _statistics;
  late final DeviceListController _devices;
  late final AlarmListController _alarms;

  @override
  void initState() {
    super.initState();
    widget.alarmNavigation?.addListener(_notificationChanged);
    widget.sessions.addListener(_sessionChanged);
    _hadAuthenticatedSession = widget.sessions.current != null;
    WidgetsBinding.instance.addPostFrameCallback((_) => _resumeNavigation());
    _devices = DeviceListController(
      widget.sessions,
      widget.deviceApi ?? PlatformDeviceApi(AppHttpClient()),
      active: false,
    );
    _alarms = AlarmListController(
      widget.sessions,
      widget.alarmApi ?? PlatformAlarmApi(AppHttpClient()),
      active: false,
    );
    _statistics = DeviceStatisticsController(
      widget.sessions,
      widget.statisticsApi ?? PlatformDeviceStatisticsApi(AppHttpClient()),
      active: false,
    );
  }

  @override
  void dispose() {
    _navigationEpoch++;
    widget.alarmNavigation?.removeListener(_notificationChanged);
    widget.sessions.removeListener(_sessionChanged);
    _statistics.dispose();
    _devices.dispose();
    _alarms.dispose();
    super.dispose();
  }

  void _notificationChanged() {
    _navigationEpoch++;
    _navigationError = null;
    _resumeNavigation();
  }

  void _sessionChanged() {
    if (!widget.sessions.busy) {
      if (_hadAuthenticatedSession && widget.sessions.current == null) {
        _cancelNavigation();
      }
      _hadAuthenticatedSession = widget.sessions.current != null;
    }
    _resumeNavigation();
  }

  void _resumeNavigation() {
    if (!mounted ||
        _navigating ||
        widget.sessions.busy ||
        widget.sessions.current == null ||
        widget.alarmNavigation?.value == null) {
      return;
    }
    unawaited(_navigateAlarm());
  }

  Future<void> _navigateAlarm() async {
    final request = widget.alarmNavigation!.value!;
    final epoch = _navigationEpoch;
    _navigating = true;
    try {
      final result = await resolveAlarmNavigation(
        request,
        widget.sessions,
        widget.store,
        _projectNavigation,
        () =>
            mounted &&
            epoch == _navigationEpoch &&
            identical(widget.alarmNavigation?.value, request),
      );
      if (!mounted ||
          epoch != _navigationEpoch ||
          result.scopeRevision != widget.sessions.scopeRevision) {
        return;
      }
      widget.alarmNavigation!.value = null;
      Navigator.of(context).popUntil((route) => route.isFirst);
      setState(() {
        _index = 1;
        _navigationError = null;
      });
      _syncVisible();
      unawaited(
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => AlarmDetailPage(
              id: result.alarmId,
              sessions: widget.sessions,
              api: widget.alarmApi ?? PlatformAlarmApi(AppHttpClient()),
            ),
          ),
        ),
      );
    } on StaleSessionRead {
      if (identical(widget.alarmNavigation?.value, request)) {
        widget.alarmNavigation!.value = null;
      }
    } catch (error) {
      if (mounted && epoch == _navigationEpoch) {
        widget.alarmNavigation!.value = null;
        setState(
          () => _navigationError = error is AppRequestFailure
              ? error.message
              : '暂时无法打开告警，请从历史列表查看。',
        );
      }
    } finally {
      _navigating = false;
      if (mounted && epoch != _navigationEpoch) _resumeNavigation();
    }
  }

  void _cancelNavigation() {
    _navigationEpoch++;
    widget.alarmNavigation?.value = null;
    _navigationError = null;
  }

  Future<void> _showProjects() async {
    _cancelNavigation();
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => ProjectNavigationPage(
          sessions: widget.sessions,
          store: widget.store,
          api: _projectNavigation,
        ),
      ),
    );
    if (mounted && changed == true) {
      setState(() => _index = 0);
      _syncVisible();
    }
  }

  void _visibility(bool value) {
    _visible = value;
    _syncVisible();
  }

  void _syncVisible() {
    _statistics.setActive(_visible && _index == 0);
    _devices.setActive(_visible && _index == 0);
    _alarms.setActive(_visible && _index == 1);
  }

  Future<void> _refreshVisible() async {
    if (!_visible || widget.sessions.current == null) return;
    if (_index == 0) {
      await Future.wait([
        if (!_statistics.loading) _statistics.reload(),
        if (!_devices.loading) _devices.refresh(),
      ]);
    } else if (_index == 1 && !_alarms.loading) {
      await _alarms.refresh();
    }
  }

  void _showAccess() => Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => ProjectAccessPage(store: widget.store),
    ),
  );
  void _showLogin() {
    final entry = widget.store.selected;
    if (entry == null) {
      _showAccess();
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => LoginPage(entry: entry, sessions: widget.sessions),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _changes,
    builder: (context, _) {
      final entry = widget.store.selected;
      final session = widget.sessions.current?.entry.id == entry?.id
          ? widget.sessions.current
          : null;
      return ForegroundRefresh(
        enabled: session != null && _index < 2,
        onActiveChanged: _visibility,
        onRefresh: _refreshVisible,
        child: PopScope(
          canPop: _index == 0,
          onPopInvokedWithResult: (didPop, result) {
            if (!didPop && _index != 0) {
              setState(() => _index = 0);
              _syncVisible();
            }
          },
          child: Scaffold(
            appBar: AppBar(title: Text(_titles[_index])),
            body: SafeArea(
              child: AnimatedSwitcher(
                duration: MediaQuery.disableAnimationsOf(context)
                    ? Duration.zero
                    : const Duration(milliseconds: 180),
                child: ListView(
                  key: ValueKey(_index),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  children: [
                    if (_navigationError != null)
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(_navigationError!),
                      ),
                    if (session != null && _index < 2)
                      const Padding(
                        padding: EdgeInsets.only(bottom: 8),
                        child: Text('前台每15秒更新当前页 · 可手动刷新'),
                      ),
                    if (widget.sessions.busy) const LinearProgressIndicator(),
                    if (entry != null)
                      Card(
                        child: ListTile(
                          leading: const Icon(Icons.folder_outlined),
                          title: Text(entry.displayName),
                          subtitle: Text(
                            session == null ? '当前项目 · 尚未登录' : '当前项目 · 已登录',
                          ),
                          onTap: _showAccess,
                        ),
                      ),
                    if (widget.sessions.error != null)
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          widget.sessions.error!,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      ),
                    if (!widget.sessions.loaded && !widget.sessions.busy)
                      OutlinedButton(
                        onPressed: () {
                          if (widget.store.loaded) {
                            widget.sessions.activate(entry);
                          } else {
                            _showAccess();
                          }
                        },
                        child: const Text('重新读取登录状态'),
                      ),
                    if (widget.sessions.pendingCount > 0)
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '有 ${widget.sessions.pendingCount} 项历史会话待远端退出。当前手机已停止使用这些会话，新的本机推送关联将等待撤销确认。',
                              ),
                              TextButton(
                                onPressed: widget.sessions.busy
                                    ? null
                                    : widget.sessions.retryPending,
                                child: const Text('重试远端退出'),
                              ),
                            ],
                          ),
                        ),
                      ),
                    if (session != null)
                      TextButton.icon(
                        onPressed: widget.sessions.busy ? null : _showProjects,
                        icon: const Icon(Icons.swap_horiz),
                        label: const Text('切换已授权项目'),
                      ),
                    if (_index == 2) ...[
                      ContentState(
                        icon: Icons.person_outline,
                        title: session?.username ?? '尚未登录',
                        message: session == null
                            ? '使用项目管理员分配的 App 账号。'
                            : '账号已通过当前项目验证。',
                        action: session == null && entry != null
                            ? FilledButton(
                                onPressed:
                                    widget.sessions.busy ||
                                        !widget.sessions.loaded
                                    ? null
                                    : _showLogin,
                                child: const Text('账号登录'),
                              )
                            : null,
                      ),
                      Card(
                        child: Column(
                          children: [
                            if (session != null) ...[
                              ListTile(
                                leading: const Icon(
                                  Icons.manage_accounts_outlined,
                                ),
                                title: const Text('账号设置'),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: widget.sessions.busy
                                    ? null
                                    : () => Navigator.of(context).push(
                                        MaterialPageRoute<void>(
                                          builder: (_) => AccountPage(
                                            notificationChannelsApi:
                                                widget.notificationChannelsApi,
                                            notificationPreferencesApi: widget
                                                .notificationPreferencesApi,
                                            sessions: widget.sessions,
                                            api:
                                                widget.accountApi ??
                                                PlatformAccountApi(
                                                  AppHttpClient(),
                                                ),
                                          ),
                                        ),
                                      ),
                              ),
                              const Divider(height: 1, indent: 56),
                            ],
                            ListTile(
                              leading: const Icon(Icons.link),
                              title: const Text('项目接入'),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: _showAccess,
                            ),
                            const Divider(height: 1, indent: 56),
                            ListTile(
                              leading: const Icon(Icons.info_outline),
                              title: const Text('关于应用'),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () => Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => const AboutPage(),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (session != null || widget.sessions.hasRetainedSession)
                        OutlinedButton(
                          onPressed: widget.sessions.busy
                              ? null
                              : widget.sessions.logout,
                          child: Text(session != null ? '退出登录' : '重试退出登录'),
                        ),
                    ] else if (_index == 0 && session != null) ...[
                      Column(
                        children: [
                          DeviceStatisticsPanel(controller: _statistics),
                          DeviceListPanel(
                            key: ValueKey(widget.sessions.scopeRevision),
                            controller: _devices,
                            alarmApi: _alarms.api,
                          ),
                        ],
                      ),
                    ] else if (_index == 1 && session != null) ...[
                      AlarmListPanel(
                        key: ValueKey(widget.sessions.scopeRevision),
                        controller: _alarms,
                        devices: _devices.api,
                      ),
                    ] else ...[
                      const SizedBox(height: 40),
                      ContentState(
                        icon: _index == 0
                            ? Icons.devices_other_outlined
                            : Icons.notifications_none,
                        title: session != null
                            ? '已登录当前项目'
                            : (_index == 0 ? '连接你的设备世界' : '关注设备的每一次告警'),
                        message: session != null
                            ? '账号已通过平台验证。'
                            : (_index == 0
                                  ? '接入项目并登录，查看设备状态与运行动态。'
                                  : '登录后查看已授权设备的告警记录。'),
                        action: FilledButton.icon(
                          onPressed: widget.sessions.busy
                              ? null
                              : (session == null && entry != null
                                    ? _showLogin
                                    : _showAccess),
                          icon: Icon(
                            session == null && entry != null
                                ? Icons.login
                                : Icons.link,
                          ),
                          label: Text(
                            entry == null
                                ? '接入项目'
                                : (session == null ? '账号登录' : '管理项目入口'),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            bottomNavigationBar: NavigationBar(
              selectedIndex: _index,
              onDestinationSelected: (index) {
                _cancelNavigation();
                setState(() => _index = index);
                _syncVisible();
              },
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.devices_other_outlined),
                  selectedIcon: Icon(Icons.devices_other),
                  label: '我的设备',
                ),
                NavigationDestination(
                  icon: Icon(Icons.notifications_none),
                  selectedIcon: Icon(Icons.notifications),
                  label: '告警历史',
                ),
                NavigationDestination(
                  icon: Icon(Icons.person_outline),
                  selectedIcon: Icon(Icons.person),
                  label: '我的',
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}
