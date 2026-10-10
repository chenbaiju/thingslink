import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import '../project_access/project_entry.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key, required this.entry, required this.sessions});
  final ProjectEntry entry;
  final SessionController sessions;
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (widget.sessions.targetId != widget.entry.id) return;
    FocusScope.of(context).unfocus();
    await widget.sessions.login(_username.text, _password.text);
    if (mounted && widget.sessions.current?.entry.id == widget.entry.id) {
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.sessions,
    builder: (context, _) => Scaffold(
      appBar: AppBar(title: const Text('账号登录')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              widget.entry.displayName,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(widget.entry.baseUrl),
            const SizedBox(height: 24),
            const Text('使用项目管理员分配的 App 账号。'),
            const SizedBox(height: 20),
            TextField(
              controller: _username,
              enabled: !widget.sessions.busy,
              autocorrect: false,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(labelText: '账号'),
            ),
            const SizedBox(height: 20),
            TextField(
              controller: _password,
              enabled: !widget.sessions.busy,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) {
                if (!widget.sessions.busy) _login();
              },
              decoration: const InputDecoration(labelText: '密码'),
            ),
            const SizedBox(height: 24),
            if (widget.sessions.error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Text(
                  widget.sessions.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            FilledButton(
              onPressed: widget.sessions.busy || !widget.sessions.loaded
                  ? null
                  : _login,
              child: Text(widget.sessions.busy ? '正在登录…' : '登录'),
            ),
          ],
        ),
      ),
    ),
  );
}
