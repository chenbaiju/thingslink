import 'package:flutter/material.dart';

import 'device_detail_page.dart';
import '../alarms/alarm_api.dart';
import 'device_api.dart';
import 'device_list_controller.dart';

class DeviceListPanel extends StatefulWidget {
  const DeviceListPanel({
    super.key,
    required this.controller,
    this.onSelected,
    this.alarmApi,
  });
  final DeviceListController controller;
  final ValueChanged<AppDevice>? onSelected;
  final AlarmApi? alarmApi;
  @override
  State<DeviceListPanel> createState() => _DeviceListPanelState();
}

class _DeviceListPanelState extends State<DeviceListPanel> {
  final _query = TextEditingController();
  String? _status;
  @override
  void initState() {
    super.initState();
    _query.text = widget.controller.query;
    _status = widget.controller.status;
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  void _search() {
    widget.controller.search(_query.text, _status);
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final state = widget.controller;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 24),
          Text('设备列表', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          if (widget.onSelected == null)
            const Text('搜索和筛选仅影响下方列表，上方统计仍为全部授权设备。'),
          const SizedBox(height: 12),
          TextField(
            controller: _query,
            maxLength: 100,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _search(),
            decoration: const InputDecoration(
              labelText: '名称或设备标识',
              prefixIcon: Icon(Icons.search),
              border: OutlineInputBorder(),
            ),
          ),
          DropdownButtonFormField<String>(
            initialValue: _status ?? '',
            isExpanded: true,
            decoration: const InputDecoration(
              labelText: '连接状态',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(value: '', child: Text('全部状态')),
              DropdownMenuItem(value: 'ONLINE', child: Text('在线')),
              DropdownMenuItem(value: 'OFFLINE', child: Text('离线')),
              DropdownMenuItem(value: 'INACTIVE', child: Text('未激活')),
            ],
            onChanged: (value) =>
                setState(() => _status = value == '' ? null : value),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              FilledButton.icon(
                onPressed: _search,
                icon: const Icon(Icons.search),
                label: const Text('搜索设备'),
              ),
              OutlinedButton(
                onPressed: state.loading ? null : state.refresh,
                child: const Text('刷新列表'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (state.loading) const LinearProgressIndicator(),
          if (state.error != null) ...[
            Text(state.error!),
            TextButton(onPressed: state.refresh, child: const Text('重试设备列表')),
          ],
          if (state.loaded && state.items.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('没有符合条件的已授权设备。'),
            ),
          for (final device in state.items)
            Card(
              child: InkWell(
                onTap: () => widget.onSelected != null
                    ? widget.onSelected!(device)
                    : Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => DeviceDetailPage(
                            id: device.id,
                            sessions: state.sessions,
                            api: state.api,
                            alarmApi: widget.alarmApi,
                          ),
                        ),
                      ),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      const Icon(Icons.devices_other),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              device.name,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            const SizedBox(height: 6),
                            Text(device.deviceKey),
                            Text(
                              '${device.deviceTypeName ?? '暂无类型信息'} · ${device.statusLabel}',
                            ),
                            Text(
                              '最近上报：${deviceTime(device.lastDataReportAt, missing: '暂无记录')}',
                            ),
                          ],
                        ),
                      ),
                      const Icon(Icons.chevron_right),
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
                  child: const Text('上一页设备'),
                ),
              if (state.canPrevious)
                TextButton(
                  onPressed: state.loading ? null : state.first,
                  child: const Text('返回设备首页'),
                ),
            ],
          ),
          if (state.nextCursor != null)
            OutlinedButton(
              onPressed: state.loading ? null : state.more,
              child: const Text('下一页设备'),
            ),
          if (state.loaded &&
              state.items.isNotEmpty &&
              state.nextCursor == null)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text('已到最后一页'),
              ),
            ),
        ],
      );
    },
  );
}
