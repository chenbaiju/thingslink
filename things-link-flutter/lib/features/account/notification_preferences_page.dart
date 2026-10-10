import '../../core/network/app_http_client.dart';
import 'notification_channels_api.dart';
import 'notification_channels_controller.dart';

import 'package:flutter/material.dart';

import '../../core/session/session_controller.dart';
import '../../shared/foreground_refresh.dart';
import 'notification_preferences_api.dart';
import 'notification_preferences_controller.dart';

class NotificationPreferencesPage extends StatefulWidget {
  const NotificationPreferencesPage({
    super.key,
    required this.sessions,
    required this.api,
    this.channelsApi,
  });
  final SessionController sessions;
  final NotificationPreferencesApi api;
  final NotificationChannelsApi? channelsApi;
  @override
  State<NotificationPreferencesPage> createState() =>
      _NotificationPreferencesPageState();
}

class _NotificationPreferencesPageState
    extends State<NotificationPreferencesPage> {
  late final _controller = NotificationPreferencesController(
    widget.sessions,
    widget.api,
  );
  late final _channels = NotificationChannelsController(
    widget.sessions,
    widget.channelsApi ?? PlatformNotificationChannelsApi(AppHttpClient()),
  );
  late final _changes = Listenable.merge([_controller, _channels]);
  @override
  void dispose() {
    _controller.dispose();
    _channels.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _changes,
    builder: (context, _) {
      final value = _controller.data;
      return ForegroundRefresh(
        enabled: !_controller.ended,
        onActiveChanged: (active) {
          _controller.setActive(active);
          _channels.setActive(active);
        },
        onRefresh: () async {
          if (!_channels.loading) await _channels.reload();
          if (!_controller.loading && !_controller.saving) {
            await _controller.reload();
          }
        },
        child: Scaffold(
          appBar: AppBar(title: const Text('通知设置')),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_controller.ended)
                  const Text('登录状态已变化，请返回后重新登录。')
                else ...[
                  const Text('App 告警通知偏好对本账号全部已授权项目生效，不影响告警历史和设备状态。'),
                  const SizedBox(height: 16),
                  if (_controller.loading || _controller.saving)
                    const LinearProgressIndicator(),
                  if (value != null)
                    Card(
                      child: SwitchListTile(
                        title: const Text('App 告警通知'),
                        subtitle: Text(
                          value.editable ? '允许本账号接收告警通知' : '当前项目只读，暂不能修改',
                        ),
                        value: value.appPushEnabled,
                        onChanged:
                            _controller.saving ||
                                widget.sessions.busy ||
                                !value.editable
                            ? null
                            : _controller.save,
                      ),
                    ),
                  if (_controller.notice != null)
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Text(_controller.notice!),
                    ),
                  if (_controller.error != null)
                    Padding(
                      padding: const EdgeInsets.all(12),
                      child: Text(
                        _controller.error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.notifications_none),
                      title: const Text('本机推送暂不可用'),
                      subtitle: const Text(
                        '当前可保存账号偏好。本机暂不能接收系统通知，开启偏好不会改变这一状态。',
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    '当前项目接收号码',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const Text('由项目管理员在 Console 配置，App 只读。'),
                  if (_channels.loading) const LinearProgressIndicator(),
                  if (_channels.error != null)
                    Text(
                      _channels.error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  if (_channels.data case final channels?) ...[
                    _channel(
                      '电话告警',
                      channels.voiceNumber,
                      channels.voiceAvailable,
                      Icons.phone_outlined,
                    ),
                    _channel(
                      '短信告警',
                      channels.smsNumber,
                      channels.smsAvailable,
                      Icons.sms_outlined,
                    ),
                    const Text('渠道未接入时，额度暂不可取得。额度仍由平台统一管理。'),
                  ],
                  TextButton.icon(
                    onPressed: _channels.loading ? null : _channels.reload,
                    icon: const Icon(Icons.refresh),
                    label: const Text('刷新接收号码'),
                  ),
                  TextButton.icon(
                    onPressed: _controller.loading || _controller.saving
                        ? null
                        : _controller.reload,
                    icon: const Icon(Icons.refresh),
                    label: const Text('刷新通知设置'),
                  ),
                ],
              ],
            ),
          ),
        ),
      );
    },
  );
  Widget _channel(
    String title,
    String? number,
    bool available,
    IconData icon,
  ) => Card(
    child: ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SelectableText(number ?? '未配置接收号码'),
          Text(available ? '当前版本暂不支持设置，请联系管理员。' : '暂不可用 · 渠道尚未接入'),
        ],
      ),
    ),
  );
}
