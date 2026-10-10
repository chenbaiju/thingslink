import 'notification_channels_api.dart';
import '../../core/network/app_http_client.dart';
import 'notification_preferences_api.dart';
import 'notification_preferences_page.dart';

import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import '../../shared/foreground_refresh.dart';
import 'account_api.dart';
import 'account_controller.dart';
import 'password_page.dart';

class AccountPage extends StatefulWidget {
  const AccountPage({
    super.key,
    required this.sessions,
    required this.api,
    this.notificationPreferencesApi,
    this.notificationChannelsApi,
  });
  final SessionController sessions;
  final AccountApi api;
  final NotificationPreferencesApi? notificationPreferencesApi;
  final NotificationChannelsApi? notificationChannelsApi;
  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  late final _controller = AccountController(widget.sessions, widget.api);
  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _controller,
    builder: (context, _) {
      final account = _controller.data;
      return ForegroundRefresh(
        enabled: !_controller.ended,
        onActiveChanged: _controller.setActive,
        onRefresh: () async {
          if (!_controller.loading) await _controller.reload();
        },
        child: Scaffold(
          appBar: AppBar(title: const Text('账号设置')),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_controller.ended)
                  const Text('登录状态已变化，请返回后重新登录。')
                else ...[
                  if (_controller.loading) const LinearProgressIndicator(),
                  if (_controller.error != null)
                    Text(
                      _controller.error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  if (account != null) ...[
                    Card(
                      child: Column(
                        children: [
                          _row('账号', account.username),
                          _row(
                            '显示名称',
                            account.displayName?.isNotEmpty == true
                                ? account.displayName!
                                : '未设置',
                          ),
                          _row('当前项目角色', account.roleLabel),
                          _row(
                            '创建时间',
                            MaterialLocalizations.of(context)
                                .formatFullDate(account.createdAt.toLocal()),
                          ),
                        ],
                      ),
                    ),
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.notifications_outlined),
                        title: const Text('通知设置'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: widget.sessions.busy
                            ? null
                            : () => Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => NotificationPreferencesPage(
                                    channelsApi: widget.notificationChannelsApi,
                                    sessions: widget.sessions,
                                    api:
                                        widget.notificationPreferencesApi ??
                                        PlatformNotificationPreferencesApi(
                                          AppHttpClient(),
                                        ),
                                  ),
                                ),
                              ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.lock_outline),
                        title: const Text('修改密码'),
                        subtitle: account.passwordChangeAllowed
                            ? null
                            : const Text('当前项目只读，暂不允许修改密码。'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap:
                            !account.passwordChangeAllowed ||
                                widget.sessions.busy
                            ? null
                            : () => Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => PasswordPage(
                                    sessions: widget.sessions,
                                    api: widget.api,
                                  ),
                                ),
                              ),
                      ),
                    ),
                  ],
                  TextButton.icon(
                    onPressed: _controller.loading ? null : _controller.reload,
                    icon: const Icon(Icons.refresh),
                    label: const Text('刷新账户资料'),
                  ),
                ],
              ],
            ),
          ),
        ),
      );
    },
  );
  Widget _row(String label, String value) =>
      ListTile(title: Text(label), subtitle: SelectableText(value));
}
