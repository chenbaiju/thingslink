import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import 'account_api.dart';
import 'password_controller.dart';

class PasswordPage extends StatefulWidget {
  const PasswordPage({super.key, required this.sessions, required this.api});
  final SessionController sessions;
  final AccountApi api;
  @override
  State<PasswordPage> createState() => _PasswordPageState();
}

class _PasswordPageState extends State<PasswordPage>
    with WidgetsBindingObserver {
  late final _controller = PasswordController(widget.sessions, widget.api);
  final _form = GlobalKey<FormState>();
  final _old = TextEditingController(),
      _new = TextEditingController(),
      _confirm = TextEditingController();
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.sessions.addListener(_sessionChanged);
  }

  void _clear() {
    _old.clear();
    _new.clear();
    _confirm.clear();
  }

  void _sessionChanged() {
    if (!_controller.sameSession && !widget.sessions.busy) _clear();
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _clear();
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final old = _old.text, next = _new.text;
    _clear();
    await _controller.submit(old, next);
  }

  @override
  void dispose() {
    widget.sessions.removeListener(_sessionChanged);
    WidgetsBinding.instance.removeObserver(this);
    _old.dispose();
    _new.dispose();
    _confirm.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller,
    builder: (context, _) {
      final result = _controller.outcome;
      final ended =
          result != null || (!_controller.sameSession && !widget.sessions.busy);
      return PopScope(
        canPop: !_controller.busy,
        child: Scaffold(
          appBar: AppBar(
            title: const Text('修改密码'),
            automaticallyImplyLeading: !_controller.busy,
          ),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                if (ended) ...[
                  Icon(
                    result == PasswordOutcome.changed
                        ? Icons.check_circle_outline
                        : Icons.info_outline,
                    size: 48,
                  ),
                  const SizedBox(height: 20),
                  Text(switch (result) {
                    PasswordOutcome.changed => '密码已修改，请使用新密码重新登录。',
                    PasswordOutcome.uncertain =>
                      '修改结果尚未确认，当前手机已停止使用原会话。请重新登录确认密码；不要重复提交修改。',
                    _ => '登录状态已变化，请返回后重新登录。',
                  }),
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: _controller.busy
                        ? null
                        : () =>
                              Navigator.of(context)
                                  .popUntil((route) => route.isFirst),
                    child: const Text('返回首页'),
                  ),
                ] else
                  Form(
                    key: _form,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text('修改后需重新登录，其他设备的登录会话也需要重新验证。'),
                        const SizedBox(height: 20),
                        _field(
                          '原密码',
                          _old,
                          (value) =>
                              value == null ||
                                  value.isEmpty ||
                                  value.length > 128
                              ? '请填写有效的原密码。'
                              : null,
                        ),
                        const SizedBox(height: 16),
                        _field(
                          '新密码',
                          _new,
                          (value) => validateNewPassword(value ?? ''),
                        ),
                        const SizedBox(height: 16),
                        _field(
                          '确认新密码',
                          _confirm,
                          (value) => value != _new.text ? '两次新密码不一致。' : null,
                        ),
                        const SizedBox(height: 12),
                        const Text('新密码至少8位。'),
                        if (_controller.error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              _controller.error!,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                        const SizedBox(height: 20),
                        FilledButton(
                          onPressed: _controller.busy || widget.sessions.busy
                              ? null
                              : _submit,
                          child: Text(_controller.busy ? '正在提交…' : '确认修改'),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
      );
    },
  );
  Widget _field(
    String label,
    TextEditingController controller,
    FormFieldValidator<String> validator,
  ) => TextFormField(
    controller: controller,
    decoration: InputDecoration(
      labelText: label,
      border: const OutlineInputBorder(),
    ),
    obscureText: true,
    autocorrect: false,
    enableSuggestions: false,
    enabled: !_controller.busy,
    validator: validator,
  );
}
