package com.things.link.bootstrap.project.cleanup;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * S12-1b2f-VERIFY1：让历史领域迁移夹具兼容当前DASHBOARD/OTA清理阶段。
 * 各领域测试仍停在自己的迁移版本，不能提前加载后续设备、IAM或support迁移；这里只回放已接受的阶段闭集，
 * 并用空域贡献器表达这些历史库中尚不存在的领域。真实OTA函数由独立最新迁移清理专项覆盖。
 */
final class ProjectCleanupDashboardCompatibilityFixture {

    /** 已接受的阶段闭集迁移是唯一约束来源，避免测试复制一份允许值后再次漂移。 */
    private static final ClassPathResource STAGE_CONSTRAINT_MIGRATION = new ClassPathResource(
            "db/migration/project/V20260920_0370__integration_cleanup_stage.sql");

    /** 工具类不持有状态。 */
    private ProjectCleanupDashboardCompatibilityFixture() {
    }

    /**
     * 在历史迁移断言完成后回放当前阶段约束，但不写Flyway历史或加载后续领域结构。
     *
     * @param owner 测试容器的数据库所有者连接
     */
    static void alignStageConstraint(JdbcTemplate owner) {
        owner.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, STAGE_CONSTRAINT_MIGRATION);
            return null;
        });
    }

    /**
     * 历史迁移库没有看板表，空域必须仍按当前固定顺序完成一次DASHBOARD批次。
     *
     * @return 仅供历史迁移夹具使用的空看板贡献器
     */
    static ProjectCleanupContributor emptyContributor() {
        return emptyContributor(ProjectCleanupStage.DASHBOARD);
    }

    /** 历史库没有OTA事实；生产OTA函数由新版本真实数据库专项覆盖。 */
    static ProjectCleanupContributor emptyOtaContributor(JdbcTemplate owner) {
        if (Boolean.TRUE.equals(owner.queryForObject("SELECT to_regclass('public.ota_firmware') IS NOT NULL"
                + " OR to_regclass('public.ota_firmware_creation_request') IS NOT NULL", Boolean.class))) {
            throw new IllegalStateException("已有OTA事实表的测试必须装配真实清理贡献器");
        }
        return emptyContributor(ProjectCleanupStage.OTA);
    }

    /** 只允许明确没有集成事实的历史库使用空贡献器；真实Key由最新迁移专项清理。 */
    static ProjectCleanupContributor emptyIntegrationContributor(JdbcTemplate owner) {
        if (Boolean.TRUE.equals(owner.queryForObject("SELECT to_regclass('public.integ_api_key') IS NOT NULL",Boolean.class)))
            throw new IllegalStateException("已有Key事实的测试必须装配真实清理贡献器");
        return emptyContributor(ProjectCleanupStage.INTEGRATION);
    }

    /** 仅供历史不存在领域的测试装配，禁止用于生产配置。 */
    private static ProjectCleanupContributor emptyContributor(ProjectCleanupStage emptyStage) {
        return new ProjectCleanupContributor() {
            /** {@inheritDoc} */
            @Override
            public ProjectCleanupStage stage() {
                return emptyStage;
            }

            /** {@inheritDoc} */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
                return ProjectCleanupBatchResult.done();
            }
        };
    }
}
