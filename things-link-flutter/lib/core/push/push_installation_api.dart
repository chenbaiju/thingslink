import '../network/app_http_client.dart';
import '../session/app_session.dart';

class PushProviderToken {
  PushProviderToken(this.channelConfigurationId, this.value);
  final String channelConfigurationId, value;
  @override
  String toString() => 'PushProviderToken[redacted]';
}

/// TL-07-C实现真实SDK；默认不可用，不能生成模拟token自动注册。
abstract interface class PushTokenSource {
  Future<PushProviderToken?> currentToken();
}

class UnavailablePushTokenSource implements PushTokenSource {
  @override
  Future<PushProviderToken?> currentToken() async => null;
}

class PushBinding {
  PushBinding(
    this.installationId,
    this.bindingId,
    this.sessionGroupId,
    this.revision,
    this.registrationId,
    this.channelConfigurationId,
    this.status,
    this.leaseExpiresAt,
    this.updatedAt,
  );
  final String installationId,
      bindingId,
      sessionGroupId,
      revision,
      registrationId,
      channelConfigurationId,
      status;
  final DateTime leaseExpiresAt, updatedAt;
  factory PushBinding.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException('安装关联响应无效');
    String uuid(String name) {
      final v = value[name];
      if (v is! String || !SessionIdentity.uuid.hasMatch(v)) {
        throw const FormatException('安装关联响应无效');
      }
      return v.toLowerCase();
    }

    final revision = value['revision'],
        channel = value['channelConfigurationId'],
        status = value['status'];
    final lease = DateTime.tryParse(value['leaseExpiresAt']?.toString() ?? '');
    final updated = DateTime.tryParse(value['updatedAt']?.toString() ?? '');
    if (revision is! String ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(revision) ||
        BigInt.parse(revision) > BigInt.parse('9223372036854775807') ||
        channel is! String ||
        channel.isEmpty ||
        channel.length > 64 ||
        !['ACTIVE', 'REVOKED'].contains(status) ||
        lease == null ||
        updated == null) {
      throw const FormatException('安装关联响应无效');
    }
    return PushBinding(
      uuid('installationId'),
      uuid('bindingId'),
      uuid('sessionGroupId'),
      revision,
      uuid('registrationId'),
      channel,
      status as String,
      lease,
      updated,
    );
  }
}

abstract interface class PushInstallationApi {
  Future<PushBinding?> read(AppSession session, String installationId);
  Future<PushBinding> register(
    AppSession session,
    String installationId,
    PushProviderToken token,
    String revision,
    String registrationId,
  );
}

class PlatformPushInstallationApi implements PushInstallationApi {
  PlatformPushInstallationApi(this.transport);
  final AppTransport transport;
  @override
  Future<PushBinding?> read(AppSession session, String installationId) async {
    try {
      return PushBinding.fromJson(
        await transport.send(
          session.entry,
          '/api/v1/app/push-installations/$installationId',
          accessToken: session.accessToken,
        ),
      );
    } on AppRequestFailure catch (e) {
      if (e.status == 404) return null;
      rethrow;
    }
  }

  @override
  Future<PushBinding> register(
    AppSession session,
    String installationId,
    PushProviderToken token,
    String revision,
    String registrationId,
  ) async => PushBinding.fromJson(
    await transport.send(
      session.entry,
      '/api/v1/app/push-installations',
      method: 'PUT',
      accessToken: session.accessToken,
      body: {
        'installationId': installationId,
        'channelConfigurationId': token.channelConfigurationId,
        'providerToken': token.value,
        'expectedRevision': revision,
        'registrationId': registrationId,
      },
    ),
  );
}
