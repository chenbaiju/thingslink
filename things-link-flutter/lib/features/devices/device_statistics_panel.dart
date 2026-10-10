import 'package:flutter/material.dart';

import 'device_statistics_controller.dart';

class DeviceStatisticsPanel extends StatelessWidget {
  const DeviceStatisticsPanel({super.key, required this.controller});
  final DeviceStatisticsController controller;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final data = controller.data;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 20),
          Text('当前项目设备概况', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          if (controller.loading) const LinearProgressIndicator(),
          if (controller.error != null) ...[
            Text(controller.error!),
            const SizedBox(height: 12),
          ],
          if (data != null) ...[
            LayoutBuilder(
              builder: (context, constraints) => Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  for (final item in [
                    ('设备总数', data.total, Icons.devices_other),
                    ('在线设备', data.online, Icons.wifi),
                    ('活跃设备', data.active24h, Icons.show_chart),
                    (
                      '报警设备',
                      data.alarming,
                      Icons.notifications_active_outlined,
                    ),
                  ])
                    SizedBox(
                      width: (constraints.maxWidth - 12) / 2,
                      child: Card(
                        margin: EdgeInsets.zero,
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(
                                item.$3,
                                color: Theme.of(context).colorScheme.primary,
                              ),
                              const SizedBox(height: 12),
                              Text(item.$1),
                              FittedBox(
                                fit: BoxFit.scaleDown,
                                alignment: Alignment.centerLeft,
                                child: Text(
                                  '${item.$2}',
                                  style: Theme.of(context)
                                      .textTheme
                                      .headlineLarge,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Text('更新于 ${data.asOf.toLocal().toString().split('.').first}'),
            if (data.total == 0) const Text('当前账号在此项目下暂无已授权设备。'),
          ],
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: controller.loading ? null : controller.reload,
            icon: const Icon(Icons.refresh),
            label: Text(controller.loading ? '正在读取…' : '刷新设备概况'),
          ),
          TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (context) => AlertDialog(
                title: const Text('统计口径'),
                content: const SingleChildScrollView(
                  child: Text(
                    '仅统计当前账号在此项目中已获授权且未删除的设备。\n\n'
                    '活跃设备：最近24小时内有有效数据上报，与设备是否在线分别统计。启用此功能前的上报记录不补算。\n\n'
                    '报警设备：当前存在未解除告警，每台设备只计一次。确认告警不等于解除。',
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('知道了'),
                  ),
                ],
              ),
            ),
            child: const Text('了解统计口径'),
          ),
        ],
      );
    },
  );
}
