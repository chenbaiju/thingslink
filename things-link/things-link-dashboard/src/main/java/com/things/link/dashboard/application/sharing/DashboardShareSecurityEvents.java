package com.things.link.dashboard.application.sharing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.things.link.dashboard.infrastructure.sharing.DashboardShareDiagnosticAppenders;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADR0101独立匿名安全诊断，仅接受固定字段和有限枚举，不是不可丢失业务审计。 */
@Service
public class DashboardShareSecurityEvents {
    /** 只编码安全投影，不反射HTTP请求或异常。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 独立context不受主logback-spring.xml热重载影响。 */ private final LoggerContext context;
    /** 所有share.security事件禁止传播到root，避免重复或其他文件留存策略。 */ private final Logger logger;
    /** 单实例异步队列，最多1024项且不反压业务。 */ private final DashboardShareDiagnosticAppenders.BoundedAsync async;
    /** 诊断丢弃仅携带固定原因。 */ private final MeterRegistry metrics;

    /**
     * 由Spring最终属性装配实际文件，不读取Logback的默认logs替代显式目录。
     * 可写临时目录测试只证明配置机制，不代表生产挂载资格。
     */
    public DashboardShareSecurityEvents(DashboardShareRuntimeProperties properties, MeterRegistry metrics) {
        this.metrics = metrics;
        context = new LoggerContext();
        context.setName("dashboard-share-security");
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        logger = context.getLogger("share.security");
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        if (!properties.enabled()) { async = null; return; }
        Path directory = Path.of(properties.securityLogPath()).toAbsolutePath().normalize();
        var file = new DashboardShareDiagnosticAppenders.ObservedRollingFile(metrics);
        file.setContext(context); file.setName("SHARE_SECURITY_FILE");
        file.setFile(directory.resolve("share-security.log").toString());
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context); encoder.setPattern("%msg%n"); encoder.setCharset(StandardCharsets.UTF_8); encoder.start();
        file.setEncoder(encoder);
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> rolling = new SizeAndTimeBasedRollingPolicy<>();
        rolling.setContext(context); rolling.setParent(file);
        rolling.setFileNamePattern(directory.resolve("share-security-%d{yyyy-MM-dd}.%i.log").toString());
        rolling.setMaxFileSize(new FileSize(10L * 1024 * 1024)); rolling.setMaxHistory(7);
        // archive最多90MiB，加当前活动文件10MiB，共100MiB；不是每种文件各100MiB。
        rolling.setTotalSizeCap(new FileSize(90L * 1024 * 1024)); rolling.setCleanHistoryOnStart(true);
        rolling.start(); file.setRollingPolicy(rolling); file.start();
        if (!file.isStarted()) { rolling.stop(); encoder.stop(); throw new IllegalStateException("匿名分享安全日志无法写入显式目录"); }
        async = new DashboardShareDiagnosticAppenders.BoundedAsync(metrics);
        async.setContext(context); async.setName("SHARE_SECURITY_ASYNC"); async.setQueueSize(1024);
        async.setDiscardingThreshold(0); async.setNeverBlock(true); async.setMaxFlushTime(5000);
        async.addAppender(file); async.start();
        if (!async.isStarted()) { file.stop(); throw new IllegalStateException("匿名分享安全日志队列未启动"); }
        logger.addAppender(async);
    }

    /** 固定12字段；无凭据/原URI/远端IP/正文/异常参数，错误文本也不能进入日志。 */
    public void record(Type type, Outcome outcome, Reason reason, String traceId, Route route,
                       UUID shareId, UUID projectId, long responseBytes, long durationMs) {
        if (async == null) return;
        try {
            if (type == null || outcome == null || reason == null || route == null || responseBytes < 0 || durationMs < 0
                    || (traceId != null && !traceId.matches("[A-Za-z0-9_-]{1,64}"))) {
                dropped(); return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("at", Instant.now().toString()); fields.put("type", type.name()); fields.put("outcome", outcome.name());
            fields.put("reason", reason.name()); fields.put("traceId", traceId); fields.put("routeTemplate", route.template());
            fields.put("actorType", "SHARE_CAPABILITY"); fields.put("actorAccountId", null);
            fields.put("shareId", shareId == null ? null : shareId.toString());
            fields.put("projectId", projectId == null ? null : projectId.toString());
            fields.put("responseBytes", responseBytes); fields.put("durationMs", durationMs);
            String event = JSON.writeValueAsString(fields);
            if (event.getBytes(StandardCharsets.UTF_8).length > 2048) { dropped(); return; }
            logger.info(event);
        } catch (RuntimeException diagnosticFailure) { dropped(); }
    }

    /** 不记录非法字段原文，仅累计低基数事实。 */
    private void dropped() { metrics.counter("dashboard.share.security.events.dropped", "reason", "invalid_event").increment(); }
    /** 只移除本Bean拥有的appender，关闭最长5秒不影响其他业务日志。 */
    @PreDestroy public void shutdown() {
        if (async != null) { logger.detachAppender(async); async.stop(); }
        context.stop();
    }
    /** 固定安全事件类型，预留WS枚举不等于开放WS入口。 */
    public enum Type { REQUEST, WS_HANDSHAKE, WS_CLOSE }
    /** 固定结果集合。 */
    public enum Outcome { ALLOWED, DENIED, ERROR }
    /** 固定原因集合，不接受调用方提供动态错误消息。 */
    public enum Reason { SUCCESS, UNAVAILABLE, INVALID_CREDENTIAL, SCOPE_DENIED, REFERER_DENIED, INVALID_INPUT, RATE_LIMITED, RESPONSE_LIMIT, INTERNAL_ERROR }
    /** 仅本片已开放模板与后续WS合同模板，不接收原始URI。 */
    public enum Route {
        /** 元信息入口。 */ CONTEXT("/api/v1/shares/{shareId}/context"),
        /** 不可变Schema入口。 */ SCHEMA("/api/v1/shares/{shareId}/schema"),
        /** 设备元信息读取。 */ SNAPSHOTS("/api/v1/shares/{shareId}/devices/snapshots/query"),
        /** 稀疏当前值读取。 */ CURRENT_VALUES("/api/v1/shares/{shareId}/devices/current-values/query"),
        /** 单变量目录页。 */ CATALOG("/api/v1/shares/{shareId}/devices/catalog"),
        /** 固定时间预设的版本化历史。 */ HISTORY("/api/v1/shares/{shareId}/devices/{deviceId}/properties/{propertyKey}/history"),
        /** 同绑定告警过滤查询。 */ ALARMS("/api/v1/shares/{shareId}/alarms/query"),
        /** 后续独立WS分享端点。 */ PROPERTIES("/ws/shares/{shareId}/properties");
        /** 固定路由模板。 */ private final String template;
        /** 枚举初始化不受客户端输入控制。 */ Route(String template) { this.template = template; }
        /** 只返回固定模板。 */ public String template() { return template; }
    }
}
