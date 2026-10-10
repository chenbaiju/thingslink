import 'package:flutter/material.dart';

import '../../shared/foreground_refresh.dart';

import '../../core/session/session_controller.dart';
import '../devices/device_api.dart';
import '../devices/device_list_controller.dart';
import '../devices/device_list_panel.dart';
import 'alarm_api.dart';
import 'alarm_list_controller.dart';
import 'alarm_detail_page.dart';

class AlarmListPanel extends StatefulWidget {
  const AlarmListPanel({
    super.key,
    required this.controller,
    required this.devices,
    this.fixedDevice,
  });
  final AlarmListController controller;
  final DeviceApi devices;
  final AppDevice? fixedDevice;
  @override
  State<AlarmListPanel> createState() => _AlarmListPanelState();
}

class _AlarmListPanelState extends State<AlarmListPanel> {
  String? _severity, _condition;
  AppDevice? _device;
  DateTimeRange? _range;
  late final int _scope = widget.controller.sessions.scopeRevision;
  @override
  void initState() {
    super.initState();
    _device = widget.fixedDevice;
    final filter = widget.controller.filter;
    if (_device == null && filter.deviceId != null) {
      _device = AppDevice(
        id: filter.deviceId!,
        deviceKey: filter.deviceId!,
        name: filter.deviceLabel ?? '已选设备',
        status: 'UNKNOWN',
      );
    }
    _severity = filter.severity;
    _condition = filter.conditionState;
    if (filter.from != null && filter.to != null) {
      _range = DateTimeRange(start: filter.from!, end: filter.to!);
    }
  }

  Future<void> _selectDevice() async {
    final result = await Navigator.of(context).push<AppDevice>(
      MaterialPageRoute(
        builder: (_) => _DevicePicker(
          sessions: widget.controller.sessions,
          api: widget.devices,
        ),
      ),
    );
    if (mounted &&
        _scope == widget.controller.sessions.scopeRevision &&
        result != null) {
      setState(() => _device = result);
    }
  }

  Future<void> _selectRange() async {
    final now = DateTime.now();
    final result = await showDateRangePicker(
      context: context,
      firstDate: DateTime(1970),
      lastDate: now,
      helpText: '选择首次异常日期（最多366天）',
    );
    if (!mounted || result == null) return;
    final end = DateTime(result.end.year, result.end.month, result.end.day + 1);
    if (end.toUtc().difference(result.start.toUtc()) >
        const Duration(days: 366)) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('时间范围不能超过366天。')));
      return;
    }
    setState(() => _range = DateTimeRange(start: result.start, end: end));
  }

  void _apply() => widget.controller.search(
    AlarmFilter(
      deviceId: _device?.id,
      deviceLabel: _device?.name,
      severity: _severity,
      conditionState: _condition,
      from: _range?.start,
      to: _range?.end,
    ),
  );
  Widget _select(
    String label,
    String? value,
    Map<String, String> choices,
    ValueChanged<String?> changed,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: DropdownButtonFormField<String>(
      initialValue: value ?? '',
      isExpanded: true,
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
      ),
      items: [
        const DropdownMenuItem(value: '', child: Text('全部')),
        for (final item in choices.entries)
          DropdownMenuItem(value: item.key, child: Text(item.value)),
      ],
      onChanged: (value) => setState(() => changed(value == '' ? null : value)),
    ),
  );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final state = widget.controller;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 16),
          Text(
            widget.fixedDevice == null
                ? '告警记录'
                : '${widget.fixedDevice!.name}的告警',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          const Text('按首次异常时间倒序。仅显示已触发告警；确认不代表解除。'),
          const SizedBox(height: 16),
          _select(
            '告警等级',
            _severity,
            alarmSeverities,
            (value) => _severity = value,
          ),
          _select(
            '解除状态',
            _condition,
            alarmConditions,
            (value) => _condition = value,
          ),
          if (widget.fixedDevice == null)
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _selectDevice,
                  icon: const Icon(Icons.devices_other),
                  label: Text(_device?.name ?? '全部设备 · 选择设备'),
                ),
                if (_device != null)
                  TextButton(
                    onPressed: () => setState(() => _device = null),
                    child: const Text('清除设备筛选'),
                  ),
              ],
            ),
          const SizedBox(height: 12),
          const Text('首次异常时间'),
          Text(
            _range == null
                ? '全部时间'
                : '${deviceTime(_range!.start)} 至 ${deviceTime(_range!.end)}（不含结束时刻）',
          ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              TextButton(
                onPressed: () => setState(() => _range = null),
                child: const Text('全部时间'),
              ),
              for (final days in [1, 7, 30])
                TextButton(
                  onPressed: () {
                    final now = DateTime.now();
                    setState(
                      () => _range = DateTimeRange(
                        start: now.subtract(Duration(days: days)),
                        end: now,
                      ),
                    );
                  },
                  child: Text(days == 1 ? '近24小时' : '近$days天'),
                ),
              TextButton(onPressed: _selectRange, child: const Text('自定义日期')),
            ],
          ),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              FilledButton.icon(
                onPressed: _apply,
                icon: const Icon(Icons.filter_list),
                label: const Text('查询告警'),
              ),
              OutlinedButton(
                onPressed: state.loading ? null : state.refresh,
                child: const Text('刷新告警'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (state.loading) const LinearProgressIndicator(),
          if (state.error != null) ...[
            Text(state.error!),
            TextButton(onPressed: state.refresh, child: const Text('重试告警列表')),
          ],
          if (state.loaded && state.items.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('没有符合条件的告警记录。'),
            ),
          for (final alarm in state.items)
            Card(
              child: InkWell(
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => AlarmDetailPage(
                      id: alarm.id,
                      sessions: state.sessions,
                      api: state.api,
                    ),
                  ),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        alarm.alarmType,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      Text(alarm.deviceName),
                      Text(alarm.deviceKey),
                      Text(
                        '${alarm.severityLabel} · ${alarm.conditionLabel} · ${alarm.ackLabel}',
                      ),
                      Text('首次异常：${deviceTime(alarm.firstConditionAt)}'),
                    ],
                  ),
                ),
              ),
            ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              Text('第${state.pageNumber}页'),
              if (state.canPrevious)
                OutlinedButton(
                  onPressed: state.loading ? null : state.previous,
                  child: const Text('上一页告警'),
                ),
              if (state.canPrevious)
                TextButton(
                  onPressed: state.loading ? null : state.first,
                  child: const Text('返回告警首页'),
                ),
            ],
          ),
          if (state.nextCursor != null)
            OutlinedButton(
              onPressed: state.loading ? null : state.more,
              child: const Text('下一页告警'),
            ),
          if (state.loaded &&
              state.items.isNotEmpty &&
              state.nextCursor == null)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: Text('已显示全部匹配告警')),
            ),
        ],
      );
    },
  );
}

class AlarmHistoryPage extends StatefulWidget {
  const AlarmHistoryPage({
    super.key,
    required this.sessions,
    required this.api,
    required this.devices,
    required this.device,
  });
  final SessionController sessions;
  final AlarmApi api;
  final DeviceApi devices;
  final AppDevice device;
  @override
  State<AlarmHistoryPage> createState() => _AlarmHistoryPageState();
}

class _AlarmHistoryPageState extends State<AlarmHistoryPage> {
  late final int _scope = widget.sessions.scopeRevision;
  late final AlarmListController _controller = AlarmListController(
    widget.sessions,
    widget.api,
    initialFilter: AlarmFilter(deviceId: widget.device.id),
    active: false,
  );
  @override
  void initState() {
    super.initState();
    _controller;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('设备告警')),
    body: SafeArea(
      child: ListenableBuilder(
        listenable: widget.sessions,
        builder: (context, _) => ForegroundRefresh(
          enabled:
              _scope == widget.sessions.scopeRevision &&
              widget.sessions.current != null,
          onActiveChanged: _controller.setActive,
          onRefresh: () async {
            if (!_controller.loading) await _controller.refresh();
          },
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (_scope != widget.sessions.scopeRevision ||
                  widget.sessions.current == null)
                const Text('会话已变化，请返回设备列表。')
              else
                AlarmListPanel(
                  controller: _controller,
                  devices: widget.devices,
                  fixedDevice: widget.device,
                ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _DevicePicker extends StatefulWidget {
  const _DevicePicker({required this.sessions, required this.api});
  final SessionController sessions;
  final DeviceApi api;
  @override
  State<_DevicePicker> createState() => _DevicePickerState();
}

class _DevicePickerState extends State<_DevicePicker> {
  late final int _scope = widget.sessions.scopeRevision;
  late final DeviceListController _controller = DeviceListController(
    widget.sessions,
    widget.api,
    active: false,
  );
  @override
  void initState() {
    super.initState();
    _controller;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('选择告警来源设备')),
    body: SafeArea(
      child: ListenableBuilder(
        listenable: widget.sessions,
        builder: (context, _) => ForegroundRefresh(
          enabled:
              _scope == widget.sessions.scopeRevision &&
              widget.sessions.current != null,
          onActiveChanged: _controller.setActive,
          onRefresh: () async {
            if (!_controller.loading) await _controller.refresh();
          },
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (_scope != widget.sessions.scopeRevision ||
                  widget.sessions.current == null)
                const Text('会话已变化，请返回告警历史。')
              else
                DeviceListPanel(
                  controller: _controller,
                  onSelected: (device) => Navigator.of(context).pop(device),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
