import 'dart:async';

import 'package:flutter/material.dart';

final appRouteObserver = RouteObserver<ModalRoute<void>>();

/// 只在前台且当前路线可见时计时；页面负责清理快照和丢弃旧响应。
class ForegroundRefresh extends StatefulWidget {
  const ForegroundRefresh({
    super.key,
    required this.onActiveChanged,
    required this.onRefresh,
    required this.child,
    this.enabled = true,
    this.interval = const Duration(seconds: 15),
  });
  final ValueChanged<bool> onActiveChanged;
  final Future<void> Function() onRefresh;
  final Widget child;
  final bool enabled;
  final Duration interval;
  @override
  State<ForegroundRefresh> createState() => _ForegroundRefreshState();
}

class _ForegroundRefreshState extends State<ForegroundRefresh>
    with WidgetsBindingObserver, RouteAware {
  ModalRoute<void>? _route;
  Timer? _timer;
  bool? _active;
  bool _refreshing = false;
  AppLifecycleState? _lifecycle;
  @override
  void initState() {
    super.initState();
    _lifecycle = WidgetsBinding.instance.lifecycleState;
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (_route != route) {
      appRouteObserver.unsubscribe(this);
      _route = route;
      if (route != null) appRouteObserver.subscribe(this, route);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _update();
    });
  }

  @override
  void didUpdateWidget(covariant ForegroundRefresh oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.enabled != widget.enabled) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _update();
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _lifecycle = state;
    _update();
  }

  @override
  void didPush() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _update();
    });
  }

  @override
  void didPopNext() => _update();
  @override
  void didPushNext() => _update();
  @override
  void didPop() => _update();
  void _update() {
    final active =
        widget.enabled &&
        (_lifecycle == null || _lifecycle == AppLifecycleState.resumed) &&
        (_route?.isCurrent ?? true);
    if (active == _active) return;
    _active = active;
    _timer?.cancel();
    _timer = null;
    widget.onActiveChanged(active);
    if (active) _timer = Timer.periodic(widget.interval, (_) => _tick());
  }

  Future<void> _tick() async {
    if (_active != true || _refreshing) return;
    _refreshing = true;
    try {
      await widget.onRefresh();
    } finally {
      _refreshing = false;
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    appRouteObserver.unsubscribe(this);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
