package com.things.link;

import com.things.link.project.application.DeploymentEntitlementPolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Flyway 已发布交错版本的受控兼容装配。
 *
 * <p>A/B 轨在 2026-08-31 并行提交迁移时，A 轨的 {@code 0100} 先进入源码，B 轨的
 * {@code 0200} 却可能先进入某些存量数据库。新库会按版本排序而正常通过，存量库则会把
 * 后拉取的 {@code 0100} 标为 {@link MigrationState#IGNORED} 并拒绝启动。已经发布的迁移
 * 不能改名或删除，否则已执行 {@code 0100} 的数据库会出现迁移缺失或重复建表。
 *
 * <p>本配置只对白名单中的已知事故临时启用一次乱序执行；任何未知乱序迁移仍然失败。
 * 这样既恢复两种存量历史的升级能力，也不把全局 {@code outOfOrder} 变成永久逃生门。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayCompatibilityConfiguration {
    private final Environment environment;

    public FlywayCompatibilityConfiguration(Environment environment) {
        this.environment = environment;
    }

    /** 已发布且允许补执行的迁移脚本；新增条目必须经过独立升级路径评审。 */
    private static final Set<String> APPROVED_OUT_OF_ORDER_SCRIPTS = Set.of(
            "V20260831_0100__app_device_bind_token.sql",
            // telemetry 与 enduser 曾在 2026-08-31 撞用同一版本 0200（见 bafeac4 改名修复），
            // 存量库可能只应用了 enduser 0200 而漏跑本迁移，需按序补执行。
            "V20260831_0200__property_aggregate_backfill.sql",
            // S14-1a 的产品目录迁移取 0230/0240：0220 已被 OTA 清理阶段占用（Flyway 版本是全仓全局的），
            // 而当时已发布库已经应用到 20260913_1030（S13 的下载授权重投）。新库按版本排序正常通过，
            // 存量库则把这两条判为 IGNORED 并拒绝启动——S14-6 的真实升级路径验收复现了这一点。
            //
            // 允许补执行是安全的：这两条迁移只**新建** sys_plan / sys_plan_revision /
            // sys_plan_entitlement / sys_plan_revision_dimension 并插入不可变修订版数据，
            // 不修改任何既有表，也不被任何 ≤20260913_1030 的已应用迁移引用；
            // 打开乱序后 Flyway 仍按版本顺序补执行（0230 → 0240 → 1040 → …），
            // 目录先于 S14 后续迁移（配额模板、订阅、订单）就位。
            // 升级路径由 `FlywayUpgradePathTests` 以「先迁到 20260913.1030 再迁到最新」真库证明。
            "V20260912_0230__plan_catalog.sql",
            "V20260912_0240__plan_catalog_product_revision_1_seed.sql"
    );

    /**
     * 用受控策略替换 Spring Boot 默认的直接 {@link Flyway#migrate()} 调用。
     *
     * @return 只放行已登记交错版本的迁移策略
     */
    @Bean
    FlywayMigrationStrategy controlledOutOfOrderMigrationStrategy() {
        return flyway -> {
            migrateWithCompatibility(flyway);
            provisionAutomationEntitlement(flyway, environment);
        };
    }

    /**
     * Flyway owner 写入单行部署选择；运行角色只有 SELECT，不能自行抬高自动化日硬限。
     * 已有行与部署配置不同时拒绝启动，变更需通过受控 owner 操作后再启动。
     */
    static void provisionAutomationEntitlement(Flyway flyway, Environment environment) {
        DeploymentEntitlementPolicy policy = new DeploymentEntitlementPolicy(environment);
        String mode = policy.nonCommercial() ? "NONCOMMERCIAL" : "COMMERCIAL";
        Long technicalLimit = policy.nonCommercial()
                ? policy.automationExecutionDailyLimit() : null;
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO public.sys_deployment_automation_entitlement
                        (singleton,entitlement_mode,automation_execution_daily_limit)
                    VALUES (true,?,?) ON CONFLICT (singleton) DO NOTHING
                    """)) {
                insert.setString(1, mode);
                if (technicalLimit == null) insert.setNull(2, java.sql.Types.BIGINT);
                else insert.setLong(2, technicalLimit);
                insert.executeUpdate();
            }
            try (PreparedStatement read = connection.prepareStatement("""
                    SELECT entitlement_mode,automation_execution_daily_limit
                      FROM public.sys_deployment_automation_entitlement WHERE singleton FOR SHARE
                    """); ResultSet row = read.executeQuery()) {
                if (!row.next() || !mode.equals(row.getString(1))
                        || !java.util.Objects.equals(technicalLimit, row.getObject(2, Long.class))) {
                    throw new IllegalStateException("数据库部署自动化额度与当前配置不一致；请通过迁移 owner 受控调整后重启");
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("迁移 owner 无法核对数据库部署自动化额度", failure);
        }
    }

    /**
     * 检查全部被忽略的版本迁移，并仅在它们都属于事故白名单时重建一次性 Flyway 实例执行。
     *
     * @param flyway Spring Boot 按正式数据源、占位符和迁移目录装配的 Flyway 实例
     */
    static void migrateWithCompatibility(Flyway flyway) {
        if (flyway.getConfiguration().isOutOfOrder()) {
            throw new FlywayException("禁止全局启用 Flyway outOfOrder；交错版本必须进入受控白名单");
        }

        List<MigrationInfo> ignoredVersionedMigrations = Arrays.stream(flyway.info().all())
                .filter(MigrationInfo::isVersioned)
                .filter(migration -> migration.getState() == MigrationState.IGNORED)
                .toList();
        verifyIgnoredMigrations(ignoredVersionedMigrations);

        if (ignoredVersionedMigrations.isEmpty()) {
            flyway.migrate();
            return;
        }

        // 复制正式 Flyway 的全部连接、目录、占位符与校验配置，只对本次白名单补迁移放开乱序。
        Flyway.configure(flyway.getConfiguration().getClassLoader())
                .configuration(flyway.getConfiguration())
                .outOfOrder(true)
                .load()
                .migrate();
    }

    /**
     * 拒绝白名单之外的任何被忽略版本，避免未来编号错误被兼容逻辑静默吞掉。
     *
     * @param ignoredVersionedMigrations Flyway 在当前数据库历史下判定为 ignored 的版本迁移
     */
    static void verifyIgnoredMigrations(List<MigrationInfo> ignoredVersionedMigrations) {
        List<String> unexpectedScripts = ignoredVersionedMigrations.stream()
                .map(MigrationInfo::getScript)
                .filter(script -> !APPROVED_OUT_OF_ORDER_SCRIPTS.contains(script))
                .sorted()
                .toList();
        if (!unexpectedScripts.isEmpty()) {
            throw new FlywayException("检测到未获批准的乱序迁移，拒绝启动: " + unexpectedScripts);
        }
    }
}
