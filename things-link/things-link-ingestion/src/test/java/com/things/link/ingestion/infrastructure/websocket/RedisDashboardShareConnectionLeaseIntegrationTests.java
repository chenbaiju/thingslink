package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.DashboardShareConnectionLease.Lease;
import com.things.link.shared.error.BusinessException;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分享合同§4真实Redis双客户端仲裁，禁止本机计数替代跨实例两连接保护。 */
class RedisDashboardShareConnectionLeaseIntegrationTests {
    /** 两个独立Lettuce连接工厂，不能共用单客户端冒称多节点竞争。 */
    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    /** 客户端线程全部回收，真实键由独立shareId及有限TTL隔离。 */
    @AfterEach void cleanup() { factories.forEach(LettuceConnectionFactory::destroy); }

    /** 跨节点同名连接仍分别占两个槽；第三个拒绝且释放后立即可重入。 */
    @Test void twoClientsEnforceTwoSlotsAndReleaseWithoutMemberCollision() {
        var first = manager(redis(), "first", Duration.ofSeconds(15));
        var second = manager(redis(), "second", Duration.ofSeconds(15));
        UUID share = UUID.randomUUID();
        Lease one = first.acquire(share, "same-session");
        Lease two = second.acquire(share, "same-session");
        assertThat(one.member()).isNotEqualTo(two.member());
        assertThatThrownBy(() -> second.acquire(share, "third"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode().code()).isEqualTo(60056));
        assertThat(first.renew(one)).isTrue();
        first.release(one); first.release(one);
        Lease replacement = second.acquire(share, "replacement");
        assertThat(second.renew(two)).isTrue();
        second.release(two); second.release(replacement);
    }

    /** 同一Redis上的八条真正并行Lua竞争只成功两条，不能发生先计数后写入的超额窗口。 */
    @Test void concurrentAcquisitionAcrossClientsNeverExceedsTwo() throws Exception {
        var first = manager(redis(), "concurrent-first", Duration.ofSeconds(15));
        var second = manager(redis(), "concurrent-second", Duration.ofSeconds(15));
        UUID share = UUID.randomUUID();
        CyclicBarrier ready = new CyclicBarrier(8);
        List<Callable<Lease>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            var manager = index % 2 == 0 ? first : second;
            String session = "session-" + index;
            calls.add(() -> {
                ready.await(5, TimeUnit.SECONDS);
                try { return manager.acquire(share, session); }
                catch (BusinessException denied) {
                    assertThat(denied.errorCode().code()).isEqualTo(60056);
                    return null;
                }
            });
        }
        List<Lease> leases = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(calls)) {
                Lease lease = future.get(5, TimeUnit.SECONDS); if (lease != null) leases.add(lease);
            }
        }
        try { assertThat(leases).hasSize(2); }
        finally { leases.forEach(first::release); }
    }

    /** 崩溃租约到期后释放额度，旧实例续期不能复活并突破新节点的两个活动槽。 */
    @Test void expiredLeasesCannotBeResurrectedByRenewal() throws Exception {
        var first = manager(redis(), "crashed", Duration.ofMillis(200));
        var second = manager(redis(), "survivor", Duration.ofSeconds(15));
        UUID share = UUID.randomUUID();
        Lease expired = first.acquire(share, "old");
        Thread.sleep(350);
        Lease one = second.acquire(share, "new-one");
        Lease two = second.acquire(share, "new-two");
        assertThat(first.renew(expired)).isFalse();
        assertThatThrownBy(() -> first.acquire(share, "third"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode().code()).isEqualTo(60056));
        second.release(one); second.release(two);
    }

    /** 真实Redis WRONGTYPE让脚本失败时申请/续约都保留首因并60055，绝不返回本机降级许可。 */
    @Test void realRedisScriptFailureClosesAdmissionAndRenewal() {
        StringRedisTemplate template = redis();
        var manager = manager(template, "unavailable", Duration.ofSeconds(15));
        UUID share = UUID.randomUUID();
        Lease active = manager.acquire(share, "existing");
        String key = "share:ws:connections:{" + share + "}";
        template.delete(key);
        template.opsForValue().set(key, "wrong-type", Duration.ofSeconds(30));
        try {
            assertThatThrownBy(() -> manager.acquire(share, "new"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.errorCode().code()).isEqualTo(60055);
                        assertThat(error.getCause()).isNotNull();
                    });
            assertThatThrownBy(() -> manager.renew(active))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.errorCode().code()).isEqualTo(60055);
                        assertThat(error.getCause()).isNotNull();
                    });
            manager.release(active);
        } finally { template.delete(key); }
    }

    /** 固定短TTL仅用于崩溃回收反例，不改变生产15秒保护。 */
    private static RedisDashboardShareConnectionLease manager(StringRedisTemplate redis, String instance, Duration ttl) {
        return new RedisDashboardShareConnectionLease(redis, instance, ttl);
    }

    /** 每次调用建立独立真实Redis客户端，容器不使用开发数据源。 */
    private StringRedisTemplate redis() {
        GenericContainer<?> container = RedisAccess.redis();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet(); factories.add(factory);
        var template = new StringRedisTemplate(factory); template.afterPropertiesSet(); return template;
    }

    /** 只获取测试基础设施容器，不为纯Lua验收创建Spring上下文。 */
    private static final class RedisAccess extends AbstractIntegrationTest {
        /** @return 共享且只用于测试的真实Redis */
        private static GenericContainer<?> redis() { return REDIS; }
    }
}
