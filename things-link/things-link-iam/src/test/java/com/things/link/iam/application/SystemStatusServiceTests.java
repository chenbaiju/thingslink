package com.things.link.iam.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 系统状态总体归并规则测试。 */
@DisplayName("系统状态汇总")
class SystemStatusServiceTests {

    /** 固定时钟让观测时间断言稳定，避免测试依赖运行速度。 */
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    /** 全部真实探针正常时总体状态才是 UP。 */
    @Test
    @DisplayName("全部依赖正常时返回 UP 与同一观测时间")
    void reportsUpWhenAllDependenciesAreUp() {
        SystemStatusService service = service(List.of(
                health("db", "PostgreSQL", "UP"),
                health("redis", "Redis", "UP")));

        SystemStatusService.Snapshot snapshot = service.get();

        assertThat(snapshot.status()).isEqualTo("UP");
        assertThat(snapshot.observedAt()).isEqualTo(NOW);
        assertThat(snapshot.dependencies()).hasSize(2);
    }

    /** 未知或停服状态必须明确降级，不能仍显示绿色。 */
    @Test
    @DisplayName("存在未知依赖时返回 DEGRADED")
    void reportsDegradedWhenDependencyIsUnknown() {
        SystemStatusService service = service(List.of(health("mail", "邮件通知", "UNKNOWN")));

        assertThat(service.get().status()).isEqualTo("DEGRADED");
    }

    /** 任一关键探针失败时总体失败，避免正常项掩盖故障项。 */
    @Test
    @DisplayName("存在失败依赖时返回 DOWN")
    void reportsDownWhenAnyDependencyIsDown() {
        SystemStatusService service = service(List.of(
                health("db", "PostgreSQL", "UP"),
                health("redis", "Redis", "DOWN")));

        assertThat(service.get().status()).isEqualTo("DOWN");
    }

    /** @param dependencies 固定健康事实 */
    private SystemStatusService service(List<SystemHealthReader.DependencyHealth> dependencies) {
        return new SystemStatusService(() -> dependencies, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** @return 测试健康事实 */
    private SystemHealthReader.DependencyHealth health(String code, String name, String status) {
        return new SystemHealthReader.DependencyHealth(code, name, status);
    }
}
