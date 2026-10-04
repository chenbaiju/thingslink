package com.things.link.support.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 通知渠道 bulkhead 与按 provider/origin 隔离的断路器组合；不保存完整 URL 或邮箱。 */
@Component
public class NotificationExternalGuard {

    /** 通知渠道占用槽位。 */
    static final String BULKHEAD_ACTIVE = "thingslink.notification.bulkhead.active";
    /** bulkhead 无槽快速拒绝。 */
    static final String BULKHEAD_REJECTED = "thingslink.notification.bulkhead.rejected";
    /** provider/origin 断路器拒绝；不把故障域 key 放入标签。 */
    static final String CIRCUIT_OPEN = "thingslink.notification.circuit.open";

    /** 邮件、Webhook 与 PUSH 各自四槽，不互相借用。 */
    private final Map<String, Semaphore> bulkheads = Map.of(
            "EMAIL", new Semaphore(4),
            "WEBHOOK", new Semaphore(4),
            "PUSH", new Semaphore(4));
    /** 1024 项、30 分钟 TTL、三次失败打开 30 秒。 */
    private final FailureCircuitBreakerRegistry circuits = new FailureCircuitBreakerRegistry(
            1024, Duration.ofMinutes(30), 3, Duration.ofSeconds(30), Clock.systemUTC());
    /** 应用指标注册表。 */
    private final MeterRegistry metrics;

    /** Spring 装配入口。 */
    @Autowired
    public NotificationExternalGuard(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 无 Spring 的领域单测入口。 */
    public NotificationExternalGuard() {
        this(new SimpleMeterRegistry());
    }

    /** 为 EMAIL/WEBHOOK/PUSH 三个固定渠道注册低基数指标。 */
    NotificationExternalGuard(MeterRegistry metrics) {
        this.metrics = metrics;
        bulkheads.forEach((channel, semaphore) -> Gauge.builder(
                        BULKHEAD_ACTIVE, semaphore, value -> 4 - value.availablePermits())
                .description("通知固定渠道当前占用外部调用槽")
                .tag("channel", channel)
                .register(metrics));
    }

    /** @return 同时取得渠道槽和故障域断路器许可时的 guard，否则为空 */
    public Guard tryAcquire(String channel, String target) {
        String normalized = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        Semaphore bulkhead = bulkheads.get(normalized);
        if (bulkhead == null || !bulkhead.tryAcquire()) {
            counter(BULKHEAD_REJECTED, normalized.isEmpty() ? "INVALID" : normalized).increment();
            return null;
        }
        FailureCircuitBreakerRegistry.Permit circuit = circuits.tryAcquire(failureDomain(normalized, target));
        if (circuit == null) {
            bulkhead.release();
            counter(CIRCUIT_OPEN, normalized).increment();
            return null;
        }
        return new Guard(bulkhead, circuit);
    }

    /** @return 只带固定渠道标签的计数器 */
    private Counter counter(String name, String channel) {
        return Counter.builder(name).tag("channel", channel).register(metrics);
    }

    /** SMTP 共享平台 provider；Webhook 仅保留规范化 origin 的不可逆摘要。 */
    private static String failureDomain(String channel, String target) {
        if ("EMAIL".equals(channel)) {
            return "SMTP:PLATFORM";
        }
        if ("PUSH".equals(channel)) {
            // ADR 0038 禁止把 provider/token 冻结进 targetSnapshot；S11 桩阶段按平台 PUSH 故障域隔离。
            return "PUSH:PLATFORM";
        }
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException exception) {
            return "WEBHOOK:INVALID";
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return "WEBHOOK:INVALID";
        }
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(origin.getBytes(StandardCharsets.UTF_8));
            return "WEBHOOK:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", exception);
        }
    }

    /** 一次真实外部调用的组合许可。 */
    public static final class Guard implements AutoCloseable {
        /** 渠道固定槽。 */
        private final Semaphore bulkhead;
        /** 故障域断路器许可。 */
        private final FailureCircuitBreakerRegistry.Permit circuit;
        /** 避免重复释放信号量。 */
        private boolean closed;

        /** 仅 guard 工厂创建。 */
        private Guard(Semaphore bulkhead, FailureCircuitBreakerRegistry.Permit circuit) {
            this.bulkhead = bulkhead;
            this.circuit = circuit;
        }

        /** 外部调用成功。 */
        public void success() {
            circuit.success();
        }

        /** 可重试网络/供应商失败。 */
        public void retryableFailure() {
            circuit.retryableFailure();
        }

        /** 永久地址/模板失败不计断路器。 */
        public void ignoredFailure() {
            circuit.ignoredFailure();
        }

        /** ADR0069：外发前事务失败只取消本地资格，不把未发生的网络调用记作成功或失败。 */
        public void abortBeforeSend() {
            circuit.cancelBeforeCall();
            close();
        }

        /** 释放渠道槽；业务结果必须在此之前收口。 */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                bulkhead.release();
            }
        }
    }
}
