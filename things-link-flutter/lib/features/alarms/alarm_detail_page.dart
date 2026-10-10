import 'dart:async';

import '../../shared/foreground_refresh.dart';

import 'package:flutter/material.dart';

import '../../core/network/app_http_client.dart';
import '../../core/session/session_controller.dart';
import '../../core/session/session_read.dart';
import '../project_access/project_entry.dart';
import 'alarm_api.dart';
import '../devices/device_api.dart';

class AlarmDetailPage extends StatefulWidget {
  const AlarmDetailPage({
    super.key,
    required this.id,
    required this.sessions,
    required this.api,
  });
  final String id;
  final SessionController sessions;
  final AlarmApi api;
  @override
  State<AlarmDetailPage> createState() => _AlarmDetailPageState();
}

class _AlarmDetailPageState extends State<AlarmDetailPage> {
  AppAlarm? _alarm;
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
        _alarm = null;
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
      _alarm = null;
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
      _alarm = null;
      _error = null;
    });
    try {
      final alarm = await readWithSession(
        widget.sessions,
        _entry,
        (token) => widget.api.detail(_entry, token, widget.id),
        valid,
      );
      if (valid()) setState(() => _alarm = alarm);
    } on StaleSessionRead {
      // 详情不跨身份恢复旧告警信息。
    } catch (failure) {
      if (valid()) {
        setState(
          () => _error = failure is AppRequestFailure
              ? (failure.status == 404 ? '告警不存在或已失去访问权限。' : failure.message)
              : '告警详情暂不可用，请重试。',
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
    final alarm = _alarm;
    return ForegroundRefresh(
      enabled: _sameSession && widget.sessions.current != null,
      onActiveChanged: _visibility,
      onRefresh: _poll,
      child: Scaffold(
        appBar: AppBar(title: const Text('告警详情')),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (_ended || _entry == null)
                const Text('会话已变化，请返回告警列表。')
              else ...[
                const Text('前台每15秒更新 · 可手动刷新'),
                if (_loading || widget.sessions.current == null)
                  const LinearProgressIndicator(),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    child: Text(_error!),
                  ),
                if (alarm != null && widget.sessions.current != null) ...[
                  Text(
                    alarm.alarmType,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '${alarm.severityLabel} · ${alarm.conditionLabel} · ${alarm.ackLabel}',
                  ),
                  for (final field in [
                    ('设备', alarm.deviceName),
                    ('设备标识', alarm.deviceKey),
                    ('首次异常时间', deviceTime(alarm.firstConditionAt)),
                    (
                      '实际触发时间',
                      deviceTime(alarm.activatedAt, missing: '平台未记录触发时间'),
                    ),
                    (
                      '解除时间',
                      deviceTime(
                        alarm.clearedAt,
                        missing: alarm.conditionState == 'ACTIVE'
                            ? '尚未解除'
                            : '平台未记录解除时间',
                      ),
                    ),
                    (
                      '确认时间',
                      deviceTime(
                        alarm.acknowledgedAt,
                        missing: alarm.ackState == 'UNACKNOWLEDGED'
                            ? '尚未确认'
                            : '平台未记录确认时间',
                      ),
                    ),
                    ('最近接收时间', deviceTime(alarm.lastReceivedAt)),
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
                  const Text('确认与解除是独立状态。此处仅查看平台记录。'),
                ],
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: _loading || !_sameSession ? null : _load,
                  icon: const Icon(Icons.refresh),
                  label: const Text('刷新告警详情'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
