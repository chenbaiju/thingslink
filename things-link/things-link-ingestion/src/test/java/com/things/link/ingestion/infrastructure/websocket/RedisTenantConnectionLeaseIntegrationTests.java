package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.TenantConnectionLease;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 Redis 验证租户 WebSocket 连接租约的跨实例并发与崩溃回收语义。 */
class RedisTenantConnectionLeaseIntegrationTests {

    /** 独立 Redis 客户端工厂，测试后主动关闭网络资源。 */
    private LettuceConnectionFactory connectionFactory;

    /** 释放 Lettuce 线程与连接，避免污染共享容器的后续测试。 */
    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** 两个实例同时竞争一个租户名额时只能一个成功；释放后另一个可立即接管。 */
    @Test
    void enforcesTenantConnectionLimitAcrossInstancesAndReleases() {
        RedisTenantConnectionLease first = new RedisTenantConnectionLease(redis(), "node-a");
        RedisTenantConnectionLease second = new RedisTenantConnectionLease(redis(), "node-b");
        UUID tenantId = Uuid7.generate();
        TenantConnectionLease.ConnectionLease firstLease = first.create(tenantId, "same-container-session");
        TenantConnectionLease.ConnectionLease secondLease = second.create(tenantId, "same-container-session");

        assertThat(first.acquire(firstLease, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);
        assertThat(second.acquire(secondLease, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.REJECTED);
        assertThat(first.renew(firstLease)).isEqualTo(TenantConnectionLease.RenewDecision.RENEWED);

        first.release(firstLease);
        assertThat(second.acquire(secondLease, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);
        second.release(secondLease);
    }

    /** NULL 显式不限和 0 禁用都无需写 Redis；之后的有限套餐仍能获得第一个名额。 */
    @Test
    void distinguishesExplicitUnlimitedFromDisabledWithoutOccupyingLease() {
        RedisTenantConnectionLease leaseManager = new RedisTenantConnectionLease(redis(), "node-semantics");
        UUID tenantId = Uuid7.generate();
        TenantConnectionLease.ConnectionLease unlimited = leaseManager.create(tenantId, "unlimited");
        TenantConnectionLease.ConnectionLease disabled = leaseManager.create(tenantId, "disabled");
        TenantConnectionLease.ConnectionLease finite = leaseManager.create(tenantId, "finite");

        assertThat(leaseManager.acquire(unlimited, null)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);
        assertThat(leaseManager.acquire(disabled, 0L)).isEqualTo(TenantConnectionLease.LeaseDecision.REJECTED);
        assertThat(leaseManager.acquire(finite, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);

        leaseManager.release(finite);
    }

    /** 崩溃连接过期后可被新实例回收；旧实例心跳只能报告 LOST，不能无条件复活绕过上限。 */
    @Test
    void expiresCrashedLeaseAndNeverRevivesItDuringHeartbeat() throws Exception {
        Duration shortTtl = Duration.ofMillis(200);
        RedisTenantConnectionLease crashed = new RedisTenantConnectionLease(redis(), "node-crashed", shortTtl);
        RedisTenantConnectionLease survivor = new RedisTenantConnectionLease(redis(), "node-survivor", shortTtl);
        UUID tenantId = Uuid7.generate();
        TenantConnectionLease.ConnectionLease crashedLease = crashed.create(tenantId, "crashed");
        TenantConnectionLease.ConnectionLease survivorLease = survivor.create(tenantId, "survivor");
        assertThat(crashed.acquire(crashedLease, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);

        Thread.sleep(shortTtl.plusMillis(100));

        assertThat(crashed.renew(crashedLease)).isEqualTo(TenantConnectionLease.RenewDecision.LOST);
        assertThat(survivor.acquire(survivorLease, 1L)).isEqualTo(TenantConnectionLease.LeaseDecision.ACQUIRED);
        // LOST 心跳不会重新 ZADD，因此 survivor 已占满后旧会话仍只能保持 LOST。
        assertThat(crashed.renew(crashedLease)).isEqualTo(TenantConnectionLease.RenewDecision.LOST);
        survivor.release(survivorLease);
    }

    /** @return 连接共享 Testcontainers Redis 的字符串模板 */
    private StringRedisTemplate redis() {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory());
        template.afterPropertiesSet();
        return template;
    }

    /** @return 延迟初始化的真实 Redis Lettuce 客户端工厂 */
    private LettuceConnectionFactory connectionFactory() {
        if (connectionFactory == null) {
            GenericContainer<?> redis = RedisContainerAccess.redis();
            connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                    redis.getHost(), redis.getMappedPort(6379)));
            connectionFactory.afterPropertiesSet();
        }
        return connectionFactory;
    }

    /** 仅暴露测试基类持有的共享 Redis，不为纯 Lua 测试启动 Spring 上下文。 */
    private static final class RedisContainerAccess extends AbstractIntegrationTest {
        /** @return 全 JVM 共享的真实 Redis Testcontainer */
        private static GenericContainer<?> redis() {
            return REDIS;
        }
    }
}
