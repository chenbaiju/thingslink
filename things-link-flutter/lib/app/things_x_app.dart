import '../core/push/installation_identity.dart';
import '../core/push/push_installation_api.dart';
import '../core/push/push_installation_controller.dart';
import '../features/project_navigation/project_navigation_api.dart';
import '../features/project_navigation/alarm_navigation.dart';
import '../features/account/notification_channels_api.dart';
import '../features/account/notification_preferences_api.dart';
import '../features/account/account_api.dart';
import '../features/alarms/alarm_api.dart';

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../features/home/home_shell.dart';
import '../shared/foreground_refresh.dart';
import '../features/devices/device_statistics.dart';
import '../features/devices/device_api.dart';
import '../core/network/app_http_client.dart';
import '../core/session/session_controller.dart';
import '../features/auth/auth_api.dart';
import '../features/project_access/project_store.dart';
import '../features/project_access/preferences_project_storage.dart';

class ThingsXApp extends StatefulWidget {
  const ThingsXApp({
    super.key,
    this.projectStore,
    this.sessions,
    this.statisticsApi,
    this.deviceApi,
    this.alarmApi,
    this.accountApi,
    this.notificationPreferencesApi,
    this.notificationChannelsApi,
    this.projectNavigationApi,
    this.alarmNavigation,
  });
  final ProjectStore? projectStore;
  final SessionController? sessions;
  final DeviceStatisticsApi? statisticsApi;
  final DeviceApi? deviceApi;
  final AlarmApi? alarmApi;
  final AccountApi? accountApi;
  final NotificationPreferencesApi? notificationPreferencesApi;
  final NotificationChannelsApi? notificationChannelsApi;
  final ProjectNavigationApi? projectNavigationApi;
  final ValueNotifier<AlarmNavigationRequest?>? alarmNavigation;
  @override
  State<ThingsXApp> createState() => _ThingsXAppState();
}

class _ThingsXAppState extends State<ThingsXApp> with WidgetsBindingObserver {
  late final ProjectStore _store;
  late final SessionController _sessions;
  PushInstallationController? _push;
  bool _sessionInitialized = false;
  @override
  void initState() {
    super.initState();
    _store = widget.projectStore ?? ProjectStore(PreferencesProjectStorage());
    final installation = PreferencesInstallationIdentity();
    _sessions =
        widget.sessions ??
        SessionController(
          InstallationSessionStorage(installation),
          PlatformAuthApi(AppHttpClient()),
        );
    if (widget.sessions == null) {
      _push = PushInstallationController(
        _sessions,
        installation,
        PlatformPushInstallationApi(AppHttpClient()),
        UnavailablePushTokenSource(),
        InstallationSessionStorage(installation, namespace: 'push.v1'),
      );
    }
    WidgetsBinding.instance.addObserver(this);
    _store.addListener(_projectChanged);
    if (_store.loaded) {
      _projectChanged();
    } else {
      unawaited(_store.load());
    }
  }

  void _projectChanged() {
    if (!_store.loaded ||
        !mounted ||
        (_sessions.projectTransition && _store.busy)) {
      return;
    }
    if (!_sessionInitialized || _sessions.targetId != _store.selected?.id) {
      _sessionInitialized = true;
      unawaited(_sessions.activate(_store.selected));
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed ||
        !_sessionInitialized ||
        _sessions.busy) {
      return;
    }
    if (_sessions.current != null) {
      unawaited(_sessions.refresh());
    } else {
      unawaited(_sessions.retryPending());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _store.removeListener(_projectChanged);
    if (widget.projectStore == null) _store.dispose();
    _push?.dispose();
    if (widget.sessions == null) _sessions.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = ColorScheme.fromSeed(seedColor: const Color(0xFF245EEA));
    return MaterialApp(
      title: 'ThingsX',
      navigatorObservers: [appRouteObserver],
      debugShowCheckedModeBanner: false,
      locale: const Locale('zh', 'CN'),
      supportedLocales: const [Locale('zh', 'CN')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: ThemeData(
        colorScheme: colors,
        scaffoldBackgroundColor: const Color(0xFFF6F8FC),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFFF6F8FC),
          foregroundColor: Color(0xFF15213B),
          centerTitle: false,
          elevation: 0,
          scrolledUnderElevation: 0,
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFF245EEA),
            foregroundColor: Colors.white,
            minimumSize: const Size(48, 48),
          ),
        ),
        navigationBarTheme: const NavigationBarThemeData(
          backgroundColor: Colors.white,
          indicatorColor: Color(0xFFE5ECFF),
        ),
      ),
      home: HomeShell(
        store: _store,
        sessions: _sessions,
        statisticsApi: widget.statisticsApi,
        deviceApi: widget.deviceApi,
        alarmApi: widget.alarmApi,
        accountApi: widget.accountApi,
        notificationPreferencesApi: widget.notificationPreferencesApi,
        notificationChannelsApi: widget.notificationChannelsApi,
        projectNavigationApi: widget.projectNavigationApi,
        alarmNavigation: widget.alarmNavigation,
      ),
    );
  }
}
