import 'dart:async';

import '../../shared/foreground_refresh.dart';

import 'package:flutter/material.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import '../project_access/project_entry.dart';
import 'device_api.dart';
import '../alarms/alarm_api.dart';
import '../alarms/alarm_list_panel.dart';

class DeviceDetailPage extends StatefulWidget {
  const DeviceDetailPage({
    super.key,
    required this.id,
    required this.sessions,
    required this.api,
    this.alarmApi,
  });
  final String id;
  final SessionController sessions;
  final DeviceApi api;
  final AlarmApi? alarmApi;
  @override
  State<DeviceDetailPage> createState() => _DeviceDetailPageState();
}

class _DeviceDetailPageState extends State<DeviceDetailPage> {
  AppDevice? _device;
  String? _error, _token;
  late final int _scope;
  late final String? _username;
  late final ProjectEntry? _entry;
  bool _loading = false, _ended = false;
  int _generation = 0;
  bool _active = false;
  bool get _sameSession =>
      !_ended &&
      _scope == widget.sessions.scopeRevision &&
      widget.sessions.current?.entry.id == _entry?.id &&
      widget.sessions.current?.username == _username;
  @override
  void initState() {
    super.initState();
    _scope = widget.sessions.scopeRevision;
    _entry = widget.sessions.current?.entry;
    _username = widget.sessions.current?.username;
    _token = widget.sessions.current?.accessToken;
    widget.sessions.addListener(_sessionChanged);
  }

  void _sessionChanged() {
    if (!mounted) return;
    final current = widget.sessions.current;
    if (_scope != widget.sessions.scopeRevision ||
        (current == null && !widget.sessions.busy) ||
        (current != null &&
            (current.entry.id != _entry?.id ||
                current.username != _username))) {
      _generation++;
      setState(() {
        _ended = true;
        _device = null;
        _error = null;
        _loading = false;
      });
      return;
    }
    final changed = current != null && current.accessToken != _token;
    if (current != null) _token = current.accessToken;
    setState(() {});
    if (_active && changed && !_loading && !_ended) unawaited(_load());
  }

  void _visibility(bool value) {
    if (_active == value) return;
    _active = value;
    _generation++;
    setState(() {
      _device = null;
      _error = null;
      _loading = false;
    });
    if (value) unawaited(_load());
  }

  Future<void> _poll() async {
    if (!_loading) await _load();
  }

  Future<void> _load() async {
    if (_entry == null || !_sameSession || !_active) return;
    final generation = ++_generation;
    bool valid() => mounted && generation == _generation && _sameSession;
    setState(() {
      _loading = true;
      _device = null;
      _error = null;
    });
    try {
      final device = await readWithSession(
        widget.sessions,
        _entry,
        (token) => widget.api.detail(_entry, token, widget.id),
        valid,
      );
      if (valid()) setState(() => _device = device);
    } on StaleSessionRead {
      // 详情不跨身份恢复旧设备信息。
    } catch (failure) {
      if (valid()) {
        setState(
          () => _error = failure is AppRequestFailure
              ? (failure.status == 404 ? '设备不存在或已失去访问权限。' : failure.message)
              : '设备详情暂不可用，请重试。',
        );
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  void dispose() {
    _generation++;
    widget.sessions.removeListener(_sessionChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final device = _device;
    return ForegroundRefresh(
      enabled: _sameSession && widget.sessions.current != null,
      onActiveChanged: _visibility,
      onRefresh: _poll,
      child: Scaffold(
        appBar: AppBar(title: const Text('设备详情')),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (_ended || _entry == null)
                const Text('会话已变化，请返回设备列表。')
              else ...[
                const Text('前台每15秒更新 · 可手动刷新'),
                if (_loading || widget.sessions.current == null)
                  const LinearProgressIndicator(),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    child: Text(_error!),
                  ),
                if (device != null && widget.sessions.current != null) ...[
                  Text(
                    device.name,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => AlarmHistoryPage(
                          sessions: widget.sessions,
                          api:
                              widget.alarmApi ??
                              PlatformAlarmApi(AppHttpClient()),
                          devices: widget.api,
                          device: device,
                        ),
                      ),
                    ),
                    icon: const Icon(Icons.notifications_outlined),
                    label: const Text('查看设备告警'),
                  ),

                  for (final field in [
                    ('设备标识', device.deviceKey),
                    ('连接状态', device.statusLabel),
                    ('设备类型', device.deviceTypeName ?? '暂无类型信息'),
                    (
                      '最近有效上报',
                      deviceTime(device.lastDataReportAt, missing: '暂无有效上报记录'),
                    ),
                    ('最近在线', deviceTime(device.lastOnlineAt)),
                    ('创建时间', deviceTime(device.createdAt)),
                    (
                      '位置',
                      device.location?.trim().isNotEmpty == true
                          ? device.location!
                          : '未填写',
                    ),
                    (
                      '描述',
                      device.description?.trim().isNotEmpty == true
                          ? device.description!
                          : '未填写',
                    ),
                  ])
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              field.$1,
                              style: Theme.of(context).textTheme.labelLarge,
                            ),
                            const SizedBox(height: 8),
                            SelectableText(field.$2),
                          ],
                        ),
                      ),
                    ),
                ],
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: _loading || !_sameSession ? null : _load,
                  icon: const Icon(Icons.refresh),
                  label: const Text('刷新设备详情'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
