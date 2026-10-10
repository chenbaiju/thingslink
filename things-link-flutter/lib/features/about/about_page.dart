import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../../shared/content_state.dart';

class AboutPage extends StatefulWidget {
  const AboutPage({super.key, this.loadInfo = PackageInfo.fromPlatform});
  final Future<PackageInfo> Function() loadInfo;

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  late Future<PackageInfo> _info;
  @override
  void initState() {
    super.initState();
    _info = widget.loadInfo();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('关于应用')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.hub_outlined, size: 64, color: Color(0xFF245EEA)),
          const SizedBox(height: 20),
          Text(
            'ThingsX',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: 8),
          const Text('连接设备，关注每一次变化', textAlign: TextAlign.center),
          const SizedBox(height: 32),
          FutureBuilder<PackageInfo>(
            future: _info,
            builder: (context, snapshot) {
              if (snapshot.hasError) {
                return ContentState(
                  icon: Icons.info_outline,
                  title: '暂时无法读取版本',
                  message: '请重试获取应用信息。',
                  action: OutlinedButton(
                    onPressed: () => setState(() {
                      _info = widget.loadInfo();
                    }),
                    child: const Text('重试'),
                  ),
                );
              }
              if (!snapshot.hasData) {
                return const Center(
                  child: CircularProgressIndicator(semanticsLabel: '正在读取版本'),
                );
              }
              final info = snapshot.requireData;
              return Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('当前版本'),
                      const SizedBox(height: 8),
                      SelectableText('${info.version} (${info.buildNumber})'),
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ),
    ),
  );
}
