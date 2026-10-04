package com.things.link.access;

import java.io.IOException;
import java.util.Set;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

/**
 * 接入进程的活动组件白名单。普通服务和仓储仍可作为被动依赖装配；
 * 新增 HTTP 入口或 Kafka 消费者默认排除，须经角色合同复审后加入。
 */
public final class AccessComponentFilter implements TypeFilter {

    private static final Set<String> HTTP_ENDPOINTS = Set.of(
            "com.things.link.device.api.controller.DeviceRegistrationController",
            "com.things.link.device.api.controller.EmqxAuthController",
            "com.things.link.device.api.controller.EmqxAclController",
            "com.things.link.device.api.controller.EmqxEventController",
            "com.things.link.ingestion.api.controller.EmqxUplinkController",
            "com.things.link.ingestion.api.controller.EmqxCommandReplyController",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpPropertyReportController",
            "com.things.link.ingestion.infrastructure.protocol.http.DeviceAccessHttpCommandController");

    private static final String TCP_DOWNLINK_CONSUMER =
            "com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer";

    private static final Set<String> TELEMETRY_ACCESS_COMPONENTS = Set.of(
            "com.things.link.telemetry.application.DeviceCommandService",
            "com.things.link.telemetry.application.DeviceCommandClaimService",
            "com.things.link.telemetry.application.DeviceCommandAccessReplyService",
            "com.things.link.telemetry.application.DeviceCommandRedeliveryService",
            "com.things.link.telemetry.application.DeviceCommandMetrics",
            "com.things.link.telemetry.application.CommandWebhookSource",
            "com.things.link.telemetry.application.DeviceMessageDebugLogWriter",
            "com.things.link.telemetry.application.MessageLogService",
            "com.things.link.telemetry.application.MessageLogRedactor");

    private static final Set<String> PLATFORM_ACTIVE_CONFIGURATIONS = Set.of(
            "com.things.link.iam.infrastructure.security.SecurityConfiguration",
            "com.things.link.iam.infrastructure.security.JwtConfiguration",
            "com.things.link.iam.infrastructure.security.JwtTokenIssuer",
            "com.things.link.enduser.infrastructure.security.AppSecurityConfiguration",
            "com.things.link.enduser.infrastructure.security.AppJwtConfiguration",
            "com.things.link.enduser.infrastructure.security.AppJwtTokenIssuer",
            "com.things.link.enduser.infrastructure.security.AppBrowserSecurityConfiguration",
            "com.things.link.enduser.infrastructure.security.AppBrowserCookieConfiguration",
            "com.things.link.enduser.application.AppAuthenticationService",
            "com.things.link.enduser.application.AppSessionService",
            "com.things.link.enduser.application.AppBrowserSessionService",
            "com.things.link.enduser.application.AppPasswordService",
            "com.things.link.iam.application.RefreshTokenService",
            "com.things.link.iam.application.AuthenticationService",
            "com.things.link.iam.application.PasswordResetService",
            "com.things.link.iam.application.RegistrationService",
            "com.things.link.iam.application.EmailVerificationMailer",
            "com.things.link.iam.application.ProjectInvitationMailer",
            "com.things.link.iam.application.ProjectInvitationRegistrationService",
            "com.things.link.iam.api.support.RefreshTokenCookie",
            "com.things.link.integration.infrastructure.security.OpenApiSecurityConfiguration",
            "com.things.link.dashboard.infrastructure.security.DashboardPublicHostSecurityConfiguration",
            "com.things.link.dashboard.infrastructure.security.DashboardShareSecurityConfiguration",
            "com.things.link.support.outbox.OutboxSchedulingConfiguration",
            "com.things.link.ingestion.infrastructure.RealtimeRedisSubscriber",
            "com.things.link.ingestion.infrastructure.RealtimeDispatchExecutorConfiguration",
            "com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration",
            "com.things.link.ingestion.infrastructure.AlarmRealtimeInvalidationBridge",
            "com.things.link.support.observability.KafkaConsumerLagMetrics");

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        var metadata = reader.getClassMetadata();
        var annotations = reader.getAnnotationMetadata();
        String name = metadata.getClassName();
        if (name.startsWith("com.things.link.ota.")
                || name.startsWith("com.things.link.export.")
                || name.startsWith("com.things.link.rule.")
                || name.startsWith("com.things.link.alarm.")
                || name.startsWith("com.things.link.task.")
                || name.startsWith("com.things.link.enduser.")
                || name.startsWith("com.things.link.dashboard.")
                || name.startsWith("com.things.link.integration.")
                || name.startsWith("com.things.link.support.notification.")
                || name.startsWith("com.things.link.support.openapi.")
                || (name.startsWith("com.things.link.telemetry.application.")
                    && !TELEMETRY_ACCESS_COMPONENTS.contains(name))
                || name.startsWith("com.things.link.telemetry.infrastructure.cache.")
                || name.startsWith("com.things.link.telemetry.api.support.")
                || name.equals("com.things.link.telemetry.infrastructure.persistence.TelemetryDailyUsageContributor")
                || name.startsWith("com.things.link.support.storage.Minio")
                || (name.startsWith("com.things.link.ingestion.infrastructure.websocket.")
                    && !name.equals("com.things.link.ingestion.infrastructure.websocket.RedisTenantConnectionLease"))
                || name.startsWith("com.things.link.ingestion.application.Realtime")
                || name.startsWith("com.things.link.ingestion.application.DashboardRealtime")
                || name.startsWith("com.things.link.ingestion.application.Ota")) {
            return true; // 平台 Worker、通知、页面聚合及其基础设施不进入接入进程。
        }
        if (PLATFORM_ACTIVE_CONFIGURATIONS.contains(name)
                || (name.contains(".websocket.") && name.endsWith("Configuration"))
                || annotations.hasAnnotation("org.springframework.web.socket.config.annotation.EnableWebSocket")) {
            return true;
        }
        if (annotations.hasAnnotation("org.springframework.web.bind.annotation.RestController")
                || annotations.hasAnnotation("org.springframework.stereotype.Controller")) {
            return !HTTP_ENDPOINTS.contains(name);
        }
        if (annotations.hasAnnotation("org.springframework.kafka.annotation.KafkaListener")
                || annotations.hasAnnotatedMethods("org.springframework.kafka.annotation.KafkaListener")) {
            return !TCP_DOWNLINK_CONSUMER.equals(name);
        }
        return false;
    }
}
