package com.things.link.support.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界、按故障域隔离的轻量失败断路器注册表。
 *
 * <p>它只保护进程资源，不是业务真相：重启后全部恢复 CLOSED。注册表使用访问顺序 LRU，并淘汰
 * 30 分钟无访问项，防止租户 Webhook origin 让内存标签集合无限增长。</p>
 */
public final class FailureCircuitBreakerRegistry {

    /** 注册表最大故障域数。 */
    private final int maximumSize;
    /** 无访问条目的淘汰时间。 */
    private final Duration idleTtl;
    /** 连续可重试失败打开阈值。 */
    private final int failureThreshold;
    /** OPEN 保持时间。 */
    private final Duration openDuration;
    /** 可测试 UTC 时钟。 */
    private final Clock clock;
    /** 访问顺序 map；所有访问都在本对象锁内。 */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    /** 创建冻结参数注册表。 */
    public FailureCircuitBreakerRegistry(
            int maximumSize,
            Duration idleTtl,
            int failureThreshold,
            Duration openDuration,
            Clock clock) {
        if (maximumSize < 1 || failureThreshold < 1 || idleTtl.isNegative() || openDuration.isNegative()) {
            throw new IllegalArgumentException("断路器注册表参数非法");
        }
        this.maximumSize = maximumSize;
        this.idleTtl = idleTtl;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** @return CLOSED 或唯一 half-open 探针可进入时的 permit；OPEN/已有探针返回空 */
    public synchronized Permit tryAcquire(String key) {
        Instant now = clock.instant();
        evict(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            trimForNewEntry();
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.lastAccess = now;
        if (entry.openedAt == null) {
            return new Permit(this, key, false, null);
        }
        if (now.isBefore(entry.openedAt.plus(openDuration)) || entry.halfOpenInFlight) {
            return null;
        }
        entry.halfOpenInFlight = true;
        entry.halfOpenIdentity = new Object();
        return new Permit(this, key, true, entry.halfOpenIdentity);
    }

    /** 成功关闭故障域并清空连续失败。 */
    private synchronized void success(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        entry.consecutiveFailures = 0;
        entry.openedAt = null;
        entry.halfOpenInFlight = false;
        entry.halfOpenIdentity = null;
        entry.lastAccess = clock.instant();
    }

    /** 可重试失败累加阈值；half-open 探针失败立即重新打开。 */
    private synchronized void failure(String key, boolean halfOpen) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        entry.lastAccess = clock.instant();
        entry.halfOpenInFlight = false;
        entry.halfOpenIdentity = null;
        entry.consecutiveFailures = halfOpen ? failureThreshold : entry.consecutiveFailures + 1;
        if (entry.consecutiveFailures >= failureThreshold) {
            entry.openedAt = entry.lastAccess;
        }
    }

    /** 永久业务错误不改变 CLOSED 连续基础设施失败；half-open 已证明链路可达，按成功关闭。 */
    private synchronized void ignored(String key, boolean halfOpen) {
        if (halfOpen) {
            success(key);
            return;
        }
        Entry entry = entries.get(key);
        if (entry != null) {
            entry.lastAccess = clock.instant();
        }
    }

    /** ADR0069：未开始外部调用不能证明渠道成功，仅释放属于本次许可的half-open探针。 */
    private synchronized void cancel(String key, Object halfOpenIdentity) {
        Entry entry = entries.get(key);
        if (entry == null) return;
        if (halfOpenIdentity != null && entry.halfOpenIdentity == halfOpenIdentity) {
            entry.halfOpenInFlight = false;
            entry.halfOpenIdentity = null;
        }
        // 保留openedAt和失败数；更新本地访问时刻不构成渠道状态恢复。
        entry.lastAccess = clock.instant();
    }

    /** 淘汰过期和超过上限的最旧项。 */
    private void evict(Instant now) {
        entries.entrySet().removeIf(entry -> entry.getValue().lastAccess != null
                && entry.getValue().lastAccess.plus(idleTtl).isBefore(now)
                && !entry.getValue().halfOpenInFlight);
    }

    /** 为新故障域淘汰最旧非在途项；全为 half-open 时拒绝扩大而复用最旧项的容量。 */
    private void trimForNewEntry() {
        while (entries.size() >= maximumSize) {
            String removable = entries.entrySet().stream()
                    .filter(entry -> !entry.getValue().halfOpenInFlight)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("断路器注册表全部故障域均在 half-open 探测"));
            entries.remove(removable);
        }
    }

    /** 单故障域可变状态，只能在注册表锁内访问。 */
    private static final class Entry {
        /** 连续可重试失败数。 */
        private int consecutiveFailures;
        /** 最近打开时间；空代表 CLOSED。 */
        private Instant openedAt;
        /** half-open 唯一探针是否在途。 */
        private boolean halfOpenInFlight;
        /** 当前探针身份，取消旧许可不得释放后继探针。 */
        private Object halfOpenIdentity;
        /** 最近访问时间。 */
        private Instant lastAccess;
    }

    /** 一次断路器进入许可；调用方必须在真实结果后选择一种收口。 */
    public static final class Permit {
        /** 所属注册表。 */
        private final FailureCircuitBreakerRegistry registry;
        /** 固定或不可逆摘要后的故障域 key。 */
        private final String key;
        /** 是否为 half-open 唯一探针。 */
        private final boolean halfOpen;
        /** 当前half-open资格的不可复用身份，CLOSED许可为空。 */
        private final Object halfOpenIdentity;
        /** 防止一个调用重复收口。 */
        private boolean completed;

        /** 仅注册表创建。 */
        private Permit(FailureCircuitBreakerRegistry registry, String key, boolean halfOpen, Object halfOpenIdentity) {
            this.registry = registry;
            this.key = key;
            this.halfOpen = halfOpen;
            this.halfOpenIdentity = halfOpenIdentity;
        }

        /** 登记成功并关闭断路器。 */
        public synchronized void success() {
            if (!completed) {
                completed = true;
                registry.success(key);
            }
        }

        /** 登记一次可重试基础设施失败。 */
        public synchronized void retryableFailure() {
            if (!completed) {
                completed = true;
                registry.failure(key, halfOpen);
            }
        }

        /** ADR0069：提交前失败尚未执行网络，幂等取消且不重置原失败或打开时间。 */
        public synchronized void cancelBeforeCall() {
            if (!completed) {
                completed = true;
                registry.cancel(key, halfOpenIdentity);
            }
        }

        /** 永久地址/模板错误不计入连续失败。 */
        public synchronized void ignoredFailure() {
            if (!completed) {
                completed = true;
                registry.ignored(key, halfOpen);
            }
        }
    }
}
