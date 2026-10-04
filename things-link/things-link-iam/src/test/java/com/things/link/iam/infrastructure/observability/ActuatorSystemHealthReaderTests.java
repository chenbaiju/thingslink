package com.things.link.iam.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/** Actuator 健康贡献者脱敏适配测试。 */
@DisplayName("Actuator 系统状态适配")
class ActuatorSystemHealthReaderTests {

    /** 只允许固定白名单进入控制台，避免内部探针或敏感详情意外成为公开契约。 */
    @Test
    @DisplayName("只读取白名单探针且不返回详情")
    void onlyReturnsWhitelistedContributors() {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor("db",
                (HealthIndicator) () -> Health.up().withDetail("url", "secret-host").build());
        registry.registerContributor("internalSecret", (HealthIndicator) () -> Health.down().build());

        var result = new ActuatorSystemHealthReader(registry).read();

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("db");
            assertThat(item.name()).isEqualTo("PostgreSQL / TimescaleDB");
            assertThat(item.status()).isEqualTo("UP");
            assertThat(item.toString()).doesNotContain("secret-host");
        });
    }

    /** 探针自身异常必须退化成 DOWN 快照，而不是让状态页返回 500。 */
    @Test
    @DisplayName("探针异常时收敛为 DOWN")
    void convertsProbeFailureToDown() {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor("redis", (HealthIndicator) () -> {
            throw new IllegalStateException("connection refused");
        });

        assertThat(new ActuatorSystemHealthReader(registry).read())
                .singleElement()
                .extracting(item -> item.status())
                .isEqualTo("DOWN");
    }
}
