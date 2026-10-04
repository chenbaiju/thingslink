package com.things.link.export.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 项目导出请求按账号与项目组合隔离的Redis固定窗口限流器。 */
@Component
public class ProjectExportRateLimiter {

    /** 一分钟内同一账号项目组合最多五次，重复非终态回读也计数。 */
    private static final int MAX_REQUESTS = 5;
    /** 固定一分钟窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);
    /** 与其他限流用途隔离的低基数键前缀。 */
    private static final String KEY_PREFIX = "project:export:rl:";
    /** 原子递增并仅为第一笔设置TTL，进程中断不会留下永久键。 */
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);
    /** 限流故障日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectExportRateLimiter.class);
    /** 多实例共享Redis入口。 */
    private final StringRedisTemplate redis;

    /** @param redis Redis字符串访问器 */
    public ProjectExportRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 领取一次请求额度；Redis异常按高成本非核心入口策略fail-closed。
     * @param accountId 已认证账号
     * @param projectId 路径项目
     * @return 未超过五次且Redis可确认时为true
     */
    public boolean tryAcquire(UUID accountId, UUID projectId) {
        if (accountId == null || projectId == null) {
            throw new IllegalArgumentException("项目导出限流身份不能为空");
        }
        String key = KEY_PREFIX + accountId + ":" + projectId;
        try {
            Long count = redis.execute(INCREMENT, List.of(key), Long.toString(WINDOW.toMillis()));
            return count != null && count <= MAX_REQUESTS;
        } catch (RuntimeException exception) {
            LOGGER.error("项目导出请求限流不可用，本次fail-closed accountId={} projectId={}",
                    accountId, projectId, exception);
            return false;
        }
    }
}
