package com.things.link.dashboard.application.sharing;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 未启用、配置缺失、16在途及固定键空间均在匿名数据库定位之前生效。 */
class DashboardShareProtectionTests {
    /** 本地临时目录只验证实际可写机制，不声明生产持久挂载。 */ @TempDir Path logDirectory;

    /** 禁用入口即503且不访问Redis，不能依赖后续控制器碰巧拒绝。 */
    @Test
    void disabledRuntimeRejectsWithoutRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DashboardShareProtectionService protection = new DashboardShareProtectionService(redis,
                new DashboardShareRuntimeProperties(false, null, false, null));
        assertCode(() -> protection.acquireSource("127.0.0.1"), 60055);
        verifyNoInteractions(redis);
    }

    /** 来源桶只有1024个编号，不随恶意来源种类增加键空间。 */
    @Test
    void untrustedSourcesMapOnlyToFixedBuckets() {
        for (int index = 0; index < 10000; index++) {
            assertThat(DashboardShareProtectionService.sourceBucket("2001:db8::" + Integer.toHexString(index)))
                    .isBetween(0, 1023);
        }
    }

    /** 16个持有者填满实例预算，重复归还不会把预算扩成17。 */
    @Test
    void inFlightBoundAndPermitCloseAreExact() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        DashboardShareProtectionService protection = new DashboardShareProtectionService(redis, enabled());
        List<DashboardShareProtectionService.SourcePermit> held = new ArrayList<>();
        for (int index = 0; index < 16; index++) held.add(protection.acquireSource("127.0.0." + index));
        assertCode(() -> protection.acquireSource("127.0.0.50"), 60056);
        held.getFirst().close(); held.getFirst().close();
        held.add(protection.acquireSource("127.0.0.51"));
        assertCode(() -> protection.acquireSource("127.0.0.52"), 60056);
        held.forEach(DashboardShareProtectionService.SourcePermit::close);
    }

    /** Redis故障归还实例槽但仍保持503，不能转本机限额继续匿名定位。 */
    @Test
    void redisFailureIsUnavailableAndDoesNotLeakPermits() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        IllegalStateException cause = new IllegalStateException("private redis endpoint");
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(cause);
        DashboardShareProtectionService protection = new DashboardShareProtectionService(redis, enabled());
        for (int index = 0; index < 20; index++) assertCode(() -> protection.acquireSource("127.0.0.1"), 60055);
        assertThatThrownBy(() -> protection.acquireSource("127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasCause(cause).hasMessageNotContaining("private redis endpoint");
        assertCode(() -> protection.requireKnown(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), 60055);
    }

    /** 正式HTTPS与显式loopback例外分开，缺路径和通配来源不能启动。 */
    @Test
    void configurationRequiresExplicitHostAndWritablePath() {
        assertThatThrownBy(() -> new DashboardShareRuntimeProperties(true, "https://example.test", false, ""))
                .isInstanceOf(IllegalArgumentException.class);
        for (String origin : List.of("http://example.test", "https://*.example.test", "https://example.test/path",
                "https://user@example.test", "https://example.test?x=1", "https://example.test#x", "null")) {
            assertThatThrownBy(() -> new DashboardShareRuntimeProperties(true, origin, false, logDirectory.toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DashboardShareRuntimeProperties(true, "http://127.0.0.1:3007", false, logDirectory.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DashboardShareRuntimeProperties(true, "http://127.0.0.1:3007", true, logDirectory.toString()).enabled()).isTrue();
    }

    /** 显式宿主与测试目录。 */
    private DashboardShareRuntimeProperties enabled() { return new DashboardShareRuntimeProperties(true, "https://example.test", false, logDirectory.toString()); }
    /** 只有稳定业务码符合拒绝合同。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode().code()).isEqualTo(code));
    }
}
