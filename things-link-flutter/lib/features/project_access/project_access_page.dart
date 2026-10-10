import 'package:flutter/material.dart';

import 'project_entry.dart';
import 'project_store.dart';
import 'project_scan_page.dart';

class ProjectAccessPage extends StatelessWidget {
  const ProjectAccessPage({super.key, required this.store});
  final ProjectStore store;

  Future<void> _run(
    BuildContext context,
    Future<void> Function() action,
  ) async {
    try {
      await action();
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(store.error ?? '操作未完成，请重试')));
      }
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: store,
    builder: (context, _) => Scaffold(
      appBar: AppBar(title: const Text('项目接入')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text('保存管理员提供的项目入口，然后使用 App 账号登录。'),
            const SizedBox(height: 16),
            if (store.busy) const LinearProgressIndicator(),
            if (store.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(
                  store.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (!store.loaded && !store.busy)
              OutlinedButton(onPressed: store.load, child: const Text('重新读取')),
            if (store.loaded) ...[
              FilledButton.icon(
                onPressed: store.busy
                    ? null
                    : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ProjectEntryForm(store: store),
                        ),
                      ),
                icon: const Icon(Icons.add),
                label: const Text('添加项目入口'),
              ),
              const SizedBox(height: 16),
              if (store.entries.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('尚未保存项目入口', textAlign: TextAlign.center),
                ),
              for (final entry in store.entries)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          entry.displayName,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        Text(entry.baseUrl),
                        Text('项目标识：${entry.projectKey}'),
                        if (store.selected?.id == entry.id) const Text('当前项目'),
                        Wrap(
                          spacing: 8,
                          children: [
                            TextButton(
                              onPressed:
                                  store.busy || store.selected?.id == entry.id
                                  ? null
                                  : () => _run(
                                      context,
                                      () => store.select(entry.id),
                                    ),
                              child: const Text('选择项目'),
                            ),
                            TextButton(
                              onPressed: store.busy
                                  ? null
                                  : () async {
                                      final confirmed = await showDialog<bool>(
                                        context: context,
                                        builder: (context) => AlertDialog(
                                          title: const Text('删除项目入口？'),
                                          content: Text(
                                            '将移除“${entry.displayName}”的接入信息，可由管理员重新提供。',
                                          ),
                                          actions: [
                                            TextButton(
                                              onPressed: () =>
                                                  Navigator.pop(context, false),
                                              child: const Text('取消'),
                                            ),
                                            TextButton(
                                              onPressed: () =>
                                                  Navigator.pop(context, true),
                                              child: const Text('删除'),
                                            ),
                                          ],
                                        ),
                                      );
                                      if (confirmed == true &&
                                          context.mounted) {
                                        await _run(
                                          context,
                                          () => store.remove(entry.id),
                                        );
                                      }
                                    },
                              child: const Text('删除入口'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ],
        ),
      ),
    ),
  );
}

class ProjectEntryForm extends StatefulWidget {
  const ProjectEntryForm({super.key, required this.store});
  final ProjectStore store;
  @override
  State<ProjectEntryForm> createState() => _ProjectEntryFormState();
}

class _ProjectEntryFormState extends State<ProjectEntryForm> {
  final _url = TextEditingController();
  final _key = TextEditingController();
  final _name = TextEditingController();
  final _import = TextEditingController();
  String? _error;
  bool _saving = false;
  @override
  void dispose() {
    for (final c in [_url, _key, _name, _import]) {
      c.dispose();
    }
    super.dispose();
  }

  void _parse() {
    try {
      final entry = ProjectEntry.parse(_import.text);
      _url.text = entry.baseUrl;
      _key.text = entry.projectKey;
      _name.text = entry.label;
      setState(() => _error = null);
    } on FormatException catch (_) {
      setState(() => _error = '接入信息格式无效或版本不支持，请检查管理员提供的内容。');
    }
  }

  Future<void> _scan() async {
    final entry = await Navigator.of(context).push<ProjectEntry>(
      MaterialPageRoute(builder: (_) => const ProjectScanPage()),
    );
    if (!mounted || entry == null) return;
    _url.text = entry.baseUrl;
    _key.text = entry.projectKey;
    _name.text = entry.label;
    setState(() => _error = null);
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final entry = ProjectEntry(
        baseUrl: _url.text,
        projectKey: _key.text,
        label: _name.text,
      );
      await widget.store.save(entry);
      if (mounted) Navigator.pop(context);
    } on FormatException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) setState(() => _error = '保存失败，请重试。输入内容已保留。');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('添加项目入口')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('请核对平台地址，确认属于你要访问的项目。'),
          OutlinedButton.icon(
            onPressed: _saving ? null : _scan,
            icon: const Icon(Icons.qr_code_scanner),
            label: const Text('扫描项目二维码'),
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _url,
            enabled: !_saving,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: const InputDecoration(
              labelText: '平台地址',
              hintText: ProjectEntry.localHttpDebugEnabled
                  ? ProjectEntry.localDebugBaseUrl
                  : 'https://平台地址',
              helperText: ProjectEntry.localHttpDebugEnabled
                  ? '本机调试可填写上方 HTTP 地址，其他平台请使用 HTTPS。'
                  : null,
              helperMaxLines: 2,
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _key,
            enabled: !_saving,
            autocorrect: false,
            decoration: const InputDecoration(labelText: '项目标识（projectKey）'),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _name,
            enabled: !_saving,
            decoration: const InputDecoration(labelText: '项目名称（选填）'),
          ),
          const SizedBox(height: 24),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: Text(_saving ? '正在保存…' : '保存并选择'),
          ),
          const SizedBox(height: 24),
          ExpansionTile(
            title: const Text('从接入信息导入'),
            children: [
              TextField(
                controller: _import,
                enabled: !_saving,
                minLines: 3,
                maxLines: 6,
                decoration: const InputDecoration(labelText: '粘贴管理员提供的接入信息'),
              ),
              TextButton(
                onPressed: _saving ? null : _parse,
                child: const Text('填入表单'),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}
