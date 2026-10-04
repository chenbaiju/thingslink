package com.things.link.dashboard.application.sharing;

import com.things.link.shared.error.BusinessException;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实Redis原子三轴预算反例；使用唯一内部ID，绝不清空共享容器或邻居键。 */
class DashboardShareProtectionRedisTests {
    /** 与实际服务脚本一致的固定槽前缀。 */ private static final String PREFIX = "things-link:{dashboard-share}:";
    /** 本测试客户端生命周期。 */ private LettuceConnectionFactory factory;
    /** 真实脚本执行入口。 */ private StringRedisTemplate redis;
    /** 第一服务实例。 */ private DashboardShareProtectionService first;
    /** 第二服务实例，模拟跨节点共享预算。 */ private DashboardShareProtectionService second;
    /** 独立日志目录。 */ @TempDir Path logDirectory;
    /** 测试唯一share身份。 */ private final UUID share = UUID.randomUUID();
    /** 测试唯一项目。 */ private final UUID project = UUID.randomUUID();
    /** 测试唯一租户。 */ private final UUID tenant = UUID.randomUUID();

    /** 只启动已有懒Redis容器，不为Lua验证创建完整Spring/PG上下文。 */
    @BeforeEach
    void setup() {
        GenericContainer<?> container = RedisAccess.redis();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        var properties = new DashboardShareRuntimeProperties(true, "https://example.test", false, logDirectory.toString());
        first = new DashboardShareProtectionService(redis, properties); second = new DashboardShareProtectionService(redis, properties);
    }
    /** 仅回收本测试网络线程；业务键独立ID并有65秒TTL。 */
    @AfterEach void cleanup() { if (factory != null) factory.destroy(); }

    /** 两实例20个竞争预留只能成功16个4MiB，失败不会部分扣项目或租户预算。 */
    @Test
    void concurrentReservationsNeverExceedSharedByteBudget() throws Exception {
        List<Callable<DashboardShareProtectionService.Reservation>> requests = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            DashboardShareProtectionService service = index % 2 == 0 ? first : second;
            requests.add(() -> {
                try { return service.reserve(share, project, tenant, 4L * 1024 * 1024); }
                catch (BusinessException limit) { assertThat(limit.errorCode().code()).isEqualTo(60056); return null; }
            });
        }
        List<DashboardShareProtectionService.Reservation> held = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(requests)) {
                var reservation = future.get(5, TimeUnit.SECONDS); if (reservation != null) held.add(reservation);
            }
        }
        try {
            assertThat(held).hasSize(16);
            for (String identity : List.of("share:" + share, "project:" + project, "tenant:" + tenant)) {
                assertThat(redis.opsForHash().get(PREFIX + "bytes:values:" + identity, "_total")).isEqualTo("67108864");
                assertThat(redis.getExpire(PREFIX + "bytes:values:" + identity)).isBetween(1L, 65L);
            }
        } finally { held.forEach(DashboardShareProtectionService.Reservation::close); }
        try (var resumed = first.reserve(share, project, tenant, 768L * 1024)) { resumed.finish(100); }
        assertThat(redis.opsForHash().get(PREFIX + "bytes:values:share:" + share, "_total")).isEqualTo("100");
    }

    /** 实际正文按发送时刻记窗，close不能退款已发送的100字节。 */
    @Test
    void settlesUnusedBytesOnceAndMovesWindowToSendTime() {
        var reservation = first.reserve(share, project, tenant, 16384);
        String index = PREFIX + "bytes:index:share:" + share;
        String member = redis.opsForZSet().range(index, 0, 0).iterator().next();
        // 把预留起点回移30秒，避免固定sleep，结算必须更新为当前Redis时间而非继承旧score。
        Double initial = redis.opsForZSet().score(index, member);
        redis.opsForZSet().add(index, member, initial - 30000);
        reservation.finish(100); reservation.close();
        assertThat(redis.opsForHash().get(PREFIX + "bytes:values:share:" + share, "_total")).isEqualTo("100");
        assertThat(redis.opsForZSet().score(index, member)).isGreaterThanOrEqualTo(initial);
        assertThatThrownBy(() -> reservation.finish(100)).isInstanceOf(IllegalStateException.class);
    }

    /** 旧预留过窗后不能无额度发送；退款不能扣到新预留金额。 */
    @Test
    void expiredReservationCannotSendOrRefundAnotherReservation() {
        var expired = first.reserve(share, project, tenant, 16384);
        String index = PREFIX + "bytes:index:share:" + share;
        String member = redis.opsForZSet().range(index, 0, 0).iterator().next();
        for (String identity : List.of("share:" + share, "project:" + project, "tenant:" + tenant)) {
            String key = PREFIX + "bytes:index:" + identity;
            redis.opsForZSet().add(key, member, redis.opsForZSet().score(key, member) - 61000);
        }
        try (var fresh = first.reserve(share, project, tenant, 16384)) {
            assertThatThrownBy(() -> expired.finish(100)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode().code()).isEqualTo(60055));
            expired.close();
            assertThat(redis.opsForHash().get(PREFIX + "bytes:values:share:" + share, "_total")).isEqualTo("16384");
            fresh.finish(50);
        }
    }

    /** 已知请求共享两实例短窗口，Redis TIME而非客户端参数决定边界。 */
    @Test
    void sharesRequestWindowsAcrossInstances() {
        for (int index = 0; index < 4; index++) first.requireKnown(share, project, tenant);
        assertThatThrownBy(() -> second.requireKnown(share, project, tenant)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(60056));
        assertThat(redis.opsForZSet().zCard(PREFIX + "request:project:" + project)).isEqualTo(4);
        assertThat(redis.getExpire(PREFIX + "request:share:" + share)).isBetween(1L, 65L);
    }

    /** 仅暴露测试工具持有的真实Redis。 */
    private static final class RedisAccess extends AbstractIntegrationTest {
        /** 全JVM既有容器。 */ static GenericContainer<?> redis() { return REDIS; }
    }
}
