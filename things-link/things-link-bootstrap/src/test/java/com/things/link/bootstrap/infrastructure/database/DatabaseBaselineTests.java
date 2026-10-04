package com.things.link.bootstrap.infrastructure.database;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库基线测试：验证 Flyway 迁移在一个全新数据库上真的能跑通，
 * 且跑完之后 TimescaleDB 确实可用。
 *
 * <p>为什么不是「跑了迁移就算数」：{@code deploy/postgres/init/01-extensions.sql}
 * 在本地开发库里已经建好了扩展，所以即使 Flyway 的基线迁移写错或根本没执行，
 * 在开发环境也照样能用 —— 问题会一直藏到部署或 CI 才暴露。
 *
 * <p>Testcontainers 起的是全新容器，只会执行 Flyway 迁移，不会执行 deploy 的
 * init 脚本。因此这个测试是「迁移脚本是否自洽」的唯一真实检验。
 */
@DisplayName("数据库基线（Flyway + TimescaleDB）")
class DatabaseBaselineTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 基线迁移已被 Flyway 记录且执行成功。
     *
     * <p>同时断言 {@code success} 为真：Flyway 对失败的迁移也会留下历史记录，
     * 只查「有没有这一行」会漏掉执行失败的情况。
     */
    @Test
    @DisplayName("基线迁移已执行且成功")
    void baselineMigrationHasBeenApplied() {
        Boolean success = jdbcTemplate.queryForObject("""
                SELECT success FROM flyway_schema_history
                 WHERE script = 'V20260801_0100__baseline.sql'
                """, Boolean.class);

        assertThat(success)
                .as("基线迁移应已被 Flyway 执行并成功")
                .isTrue();
    }

    /**
     * 迁移声明的三个扩展都已安装。
     */
    @Test
    @DisplayName("扩展 timescaledb / pgcrypto / pg_trgm 均已安装")
    void requiredExtensionsAreInstalled() {
        var installed = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension", String.class);

        assertThat(installed).contains("timescaledb", "pgcrypto", "pg_trgm");
    }

    /**
     * hypertable 全流程可用。
     *
     * <p>只查扩展是否存在不够：扩展装了但 {@code shared_preload_libraries} 没配时，
     * {@code create_hypertable} 才会失败。这个差别如果留到 S3 建
     * {@code device_property_point} 时才发现，代价大得多。
     *
     * <p>注意分区列 {@code ts} 必须包含在主键内 —— 这是 TimescaleDB 的硬性要求，
     * 架构文档第 9 节的表设计已经满足，新建时序表时别忘。
     */
    @Test
    @DisplayName("hypertable 已由迁移建成，且可写可查")
    void hypertableIsUsable() {
        // 表由 db/migration-test/V20260801_9900__hypertable_probe.sql 创建。
        // 建表不在测试代码里做：S0-5C 之后应用运行时用的是 thingslink_app，
        // 它没有 CREATE 权限 —— 建表只能走迁移，这是刻意收紧的。
        Boolean isHypertable = jdbcTemplate.queryForObject("""
                SELECT count(*) > 0 FROM timescaledb_information.hypertables
                 WHERE hypertable_name = 'hypertable_probe'
                """, Boolean.class);
        assertThat(isHypertable)
                .as("扩展装了但 shared_preload_libraries 没配时，create_hypertable 会失败；"
                        + "只查扩展存在是不够的")
                .isTrue();

        jdbcTemplate.update("INSERT INTO hypertable_probe VALUES (now(), 26.5)");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hypertable_probe", Integer.class);
        assertThat(count).isPositive();
    }

    /**
     * Hibernate 的 ddl-auto 为 validate。
     *
     * <p>这条守的是 README.md 的「never rely on auto-DDL」。写成 update 的后果很隐蔽：
     * 本地会正常工作，因为 Hibernate 悄悄把缺的表建了出来，于是迁移脚本写错也不会
     * 被发现，直到部署到没有兜底的环境才暴露。
     */
    @Test
    @DisplayName("ddl-auto 为 validate，schema 只由 Flyway 管理")
    void hibernateDoesNotManageSchema(@Autowired org.springframework.core.env.Environment environment) {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
    }

}
