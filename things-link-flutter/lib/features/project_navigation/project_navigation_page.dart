import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import '../../core/session/app_session.dart';
import '../../core/session/session_read.dart';
import '../../core/network/app_http_client.dart';
import '../project_access/project_store.dart';
import 'project_navigation_api.dart';

class ProjectNavigationPage extends StatefulWidget {
  const ProjectNavigationPage({
    super.key,
    required this.sessions,
    required this.store,
    required this.api,
  });
  final SessionController sessions;
  final ProjectStore store;
  final ProjectNavigationApi api;
  @override
  State<ProjectNavigationPage> createState() => _ProjectNavigationPageState();
}

class _ProjectNavigationPageState extends State<ProjectNavigationPage> {
  AuthorizedProjectPage? _page;
  String? _error;
  bool _busy = false, _switching = false, _ended = false;
  late final int _scope = widget.sessions.scopeRevision;
  @override
  void initState() {
    super.initState();
    widget.sessions.addListener(_changed);
    unawaited(_load(null));
  }

  void _changed() {
    if (!mounted || _switching) return;
    if (_scope != widget.sessions.scopeRevision ||
        (widget.sessions.current == null && !widget.sessions.busy)) {
      setState(() {
        _ended = true;
        _page = null;
        _error = '账号或项目已变化，请返回后重新进入。';
      });
    }
  }

  Future<void> _load(String? cursor) async {
    if (_busy || _ended) return;
    final entry = widget.sessions.current?.entry;
    if (entry == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await readWithSession(
        widget.sessions,
        entry,
        (token) => widget.api.page(entry, token, cursor),
        () => mounted && !_ended,
      );
      if (mounted && !_ended) setState(() => _page = result);
    } on StaleSessionRead {
      /* 旧身份不发布。 */
    } catch (e) {
      if (mounted && !_ended) {
        setState(() {
          _page = null;
          _error = e is AppRequestFailure ? e.message : '暂时无法读取项目，请重试。';
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _select(AuthorizedProject project) async {
    final page = _page;
    if (_busy || page == null || widget.sessions.busy) return;
    if (project.id == page.projectId) {
      Navigator.of(context).pop(false);
      return;
    }
    setState(() {
      _busy = true;
      _switching = true;
      _error = null;
      _page = null;
    });
    AppSession? issued;
    await widget.sessions.switchProject((source) async {
      issued = await widget.api.switchProject(
        source,
        page.identity,
        project.id,
      );
      return issued!;
    }, widget.store.save);
    if (!mounted) return;
    final session = widget.sessions.current;
    if (session != null &&
        identical(session, issued) &&
        session.entry.projectKey == project.key &&
        widget.sessions.error == null) {
      Navigator.of(context).pop(true);
    } else {
      setState(() {
        _busy = false;
        _switching = false;
        _ended = true;
        _error = widget.sessions.error ?? '项目切换未完成，请返回后重试。';
      });
    }
  }

  @override
  void dispose() {
    widget.sessions.removeListener(_changed);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_switching,
    child: Scaffold(
      appBar: AppBar(title: const Text('切换已授权项目')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text('选择要查看的项目。当前账号在各项目中的设备权限分别生效。'),
            const SizedBox(height: 16),
            if (_busy) const LinearProgressIndicator(),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(_error!),
              ),
            if (_page case final page?) ...[
              for (final project in page.items)
                Card(
                  child: ListTile(
                    title: Text(project.name),
                    subtitle: Text(
                      project.id == page.projectId ? '当前项目' : '已授权项目',
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: _busy ? null : () => _select(project),
                  ),
                ),
              if (page.items.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('本次扫描没有可进入的项目。'),
                ),
              if (page.nextCursor != null)
                OutlinedButton(
                  onPressed: _busy ? null : () => _load(page.nextCursor),
                  child: const Text('继续查看项目'),
                ),
            ],
            if (!_ended)
              TextButton(
                onPressed: _busy ? null : () => _load(null),
                child: const Text('重新读取项目'),
              ),
          ],
        ),
      ),
    ),
  );
}
