package com.things.link;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FlywayCompatibilityConfiguration} 的乱序迁移白名单门禁测试。
 */
@DisplayName("Flyway 存量库交错迁移兼容策略")
class FlywayCompatibilityConfigurationTests {

    /**
     * 已发布的设备绑定令牌迁移允许在 B 轨 0200 之后补执行。
     */
    @Test
    @DisplayName("只允许已登记的 20260831.0100 补迁移")
    void allowsRegisteredCompatibilityMigration() {
        MigrationInfo migration = migration("V20260831_0100__app_device_bind_token.sql");

        assertThatCode(() -> FlywayCompatibilityConfiguration.verifyIgnoredMigrations(List.of(migration)))
                .doesNotThrowAnyException();
    }

    /**
     * S14-1a 的产品目录迁移取 0230/0240（0220 已被 OTA 清理阶段占用，Flyway 版本是全仓全局的），
     * 而已发布库可能已应用到 20260913_1030（S13 下载授权重投）：存量库会把这两条判为 IGNORED，
     * 必须按受控白名单补执行（真实栈升级由 S14-6d 验证：1030 → 0110）。
     */
    @Test
    @DisplayName("允许已登记的 S14 目录迁移补执行")
    void allowsRegisteredS14CatalogMigrations() {
        assertThatCode(() -> FlywayCompatibilityConfiguration.verifyIgnoredMigrations(List.of(
                migration("V20260912_0230__plan_catalog.sql"),
                migration("V20260912_0240__plan_catalog_product_revision_1_seed.sql"))))
                .doesNotThrowAnyException();
    }

    /**
     * 与 enduser 撞版本号而漏跑的 telemetry 回补迁移允许按序补执行。
     */
    @Test
    @DisplayName("只允许已登记的 20260831.0200 补迁移")
    void allowsRegisteredTelemetryBackfillMigration() {
        MigrationInfo migration = migration("V20260831_0200__property_aggregate_backfill.sql");

        assertThatCode(() -> FlywayCompatibilityConfiguration.verifyIgnoredMigrations(List.of(migration)))
                .doesNotThrowAnyException();
    }

    /**
     * 未登记的低版本必须继续失败，不能因一次事故永久放松全局递增规则。
     */
    @Test
    @DisplayName("拒绝未登记的乱序迁移")
    void rejectsUnregisteredOutOfOrderMigration() {
        MigrationInfo migration = migration("V20260830_9999__unexpected.sql");

        assertThatThrownBy(() -> FlywayCompatibilityConfiguration.verifyIgnoredMigrations(List.of(migration)))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V20260830_9999__unexpected.sql");
    }

    /**
     * 构造只暴露脚本名的迁移描述，避免单元测试依赖数据库或 Flyway 内部实现类。
     *
     * @param script Flyway 解析后的脚本文件名
     * @return 迁移描述桩
     */
    private MigrationInfo migration(String script) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getScript()).thenReturn(script);
        return migration;
    }
}
