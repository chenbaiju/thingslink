package com.things.link.bootstrap.contract;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库表命名与注释规范的强制检查（ADR 0014、架构文档 9.1、开发实施手册 6.8）。
 *
 * <p><b>为什么必须是测试而不是约定。</b>规范写在文档里只在有人去读的时候有效。
 * 表名前缀与列注释这两件事的共同点是：漏掉了不会有任何症状 —— 功能正常、
 * 其余测试全绿，只是几个月后没人说得清某一列存的是什么、某张表属于谁。
 * 等到那时再补，要补的是几十张表，而写注释的人已经不记得当初的取舍了。
 *
 * <p>所以强制点放在这里：新建一张表却没写前缀或漏了列注释，当场构建失败。
 *
 * <p>本类跑在 bootstrap 模块，因为只有这里的 Flyway 配置聚合了全部模块的迁移，
 * 能看到完整 schema。放在任何单个业务模块里都只能检查自己那几张表。
 */
@DisplayName("数据库命名与注释规范（ADR 0014）")
class DatabaseNamingConventionTests extends AbstractIntegrationTest {

    /**
     * 允许的表名前缀。<b>这份清单就是规范本身</b>，新增业务域时在这里登记。
     *
     * <p>前缀划分的是「平台基建 vs IoT 业务域」，不是 Maven 模块（ADR 0014）：
     * 模块归属由包名表达，表名再带一次的话，将来拆分或合并模块就要改表名，
     * 而改表名是要动迁移、代码和所有排查笔记的。
     *
     * <p>需要新前缀时，先在 ADR 0014 的前缀表里加一行，再改这里 —— 顺序反了的话，
     * 清单会慢慢长出一堆没人解释得清的前缀。
     */
    private static final Set<String> ALLOWED_TABLE_PREFIXES = Set.of(
            // 平台基建：账号、租户、项目、成员、审计、幂等、配额等。
            // 它们不属于任何业务域，是整个平台的骨架
            "sys_",
            // 设备域（S2）：设备类型、物模型、设备档案、凭据、影子、分组、拓扑
            "dev_",
            // 时序域（S3）：属性点、事件、命令及其执行记录。TimescaleDB hypertable
            "ts_",
            // 规则与脚本域（S8）：消息规则、编解码脚本、自动化、场景
            "rule_",
            // 告警域（S6）：告警规则、实例、事件、通知
            "alarm_",
            // 任务域（S7）：任务、调度、目标、执行记录
            "task_",
            // 终端用户与应用域（S11）：项目应用、终端用户、绑定关系、推送令牌
            "app_",
            // 看板与大屏域（S12）
            "dash_",
            // OTA 域（S13）：固件、灰度批次、升级任务
            "ota_",
            // 集成域（S14）：API Key、Webhook 订阅与投递、物联卡
            "integ_");

    /**
     * 豁免命名与注释检查的表，每一类都要写明理由。
     *
     * <p>清单保持极短是刻意的：豁免越容易，规范越没有意义。
     */
    private static final Set<String> EXEMPT_TABLES = Set.of(
            // Flyway 自己的历史表，表结构由 Flyway 定义，我们无权也不该改
            "flyway_schema_history",
            // PostGIS维护的坐标系参考目录，不是ThingsLink业务事实表。
            "spatial_ref_sys");

    /**
     * 探针表的后缀。它们只存在于 {@code db/migration-test}，不进生产迁移路径，
     * 也不表达任何业务语义 —— 用后缀而不是逐个登记表名，是为了让新增探针表
     * 不必回来改这个清单。
     */
    private static final String PROBE_TABLE_SUFFIX = "_probe";
    /** TimescaleDB hypertable 创建的内部触发器，不由项目命名规范控制。 */
    private static final Set<String> EXEMPT_TRIGGERS = Set.of("ts_insert_blocker", "ts_cagg_invalidation_trigger");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("每张表都带已登记的业务前缀")
    void everyTableHasRegisteredPrefix() {
        List<String> tables = businessTables();

        assertThat(tables)
                .as("这些表名没有已登记的前缀。平台基建表用 sys_，IoT 业务表用领域前缀"
                        + "（见 ADR 0014）；确需新前缀时先在 ADR 里登记，再加进 "
                        + "ALLOWED_TABLE_PREFIXES")
                .allSatisfy(table -> assertThat(ALLOWED_TABLE_PREFIXES)
                        .anySatisfy(prefix -> assertThat(table).startsWith(prefix)));
    }

    /**
     * 每张表都有表注释。
     *
     * <p>表注释回答的是「这张表在业务里是什么」，是排查时第一个要看的东西 ——
     * 而它恰恰是最容易在赶进度时省掉的一行。
     */
    @Test
    @DisplayName("每张表都有 COMMENT ON TABLE")
    void everyTableHasComment() {
        List<String> undocumented = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                   AND obj_description(c.oid, 'pg_class') IS NULL
                 ORDER BY c.relname
                """, String.class).stream().filter(this::isBusinessTable).toList();

        assertThat(undocumented)
                .as("这些表没有 COMMENT ON TABLE。在建表迁移里补一行（ADR 0014）")
                .isEmpty();
    }

    /**
     * <b>本类最重要的一条。</b>每一列都必须有列注释。
     *
     * <p>迁移文件里的 {@code --} 行内注释解释「为什么这么设计」，但它只存在于
     * 源码仓库；{@code COMMENT ON COLUMN} 才进数据库目录，是 DBeaver、
     * {@code psql \d+}、{@code pg_dump} 和将来任何数据字典工具唯一看得到的说明。
     * 两者服务不同的读者，写了前者不等于可以省掉后者。
     *
     * <p>失败时报的是「表.列」的完整清单，直接照着补即可。
     */
    @Test
    @DisplayName("每一列都有 COMMENT ON COLUMN")
    void everyColumnHasComment() {
        List<String> undocumented = jdbcTemplate.queryForList("""
                SELECT c.relname || '.' || a.attname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                   AND col_description(c.oid, a.attnum) IS NULL
                 ORDER BY c.relname, a.attnum
                """, String.class).stream()
                .filter(column -> isBusinessTable(column.substring(0, column.indexOf('.'))))
                .toList();

        assertThat(undocumented)
                .as("这些列没有 COMMENT ON COLUMN。在对应迁移末尾补上 —— 行内 -- 注释"
                        + "只存在于源码，进不了数据库目录（ADR 0014）")
                .isEmpty();
    }

    /**
     * 索引、约束、触发器名以所属表的全名开头。
     *
     * <p><b>索引名在 PostgreSQL 里是 schema 内全局唯一的</b> —— 它与表、视图、序列共用
     * {@code pg_class} 命名空间。所以某张表一旦占用了 {@code name_idx} 这种名字，
     * 之后任何一张表都不能再用它，而「给 name 列建索引」是到处都会重复的需求。
     * 带表名前缀天然避开这类撞名。
     *
     * <p>约束名与触发器名是<b>每张表内</b>唯一，没有撞名风险，要求它们带表名纯粹是为了
     * 排障可读：唯一冲突报的是
     * {@code duplicate key value violates unique constraint "sys_account_email_uk"} ——
     * 名字里有表名就一眼知道是哪张表，叫 {@code email_uk} 就得再查一次。
     *
     * <p>PostgreSQL 自动生成的 {@code xxx_pkey} / {@code xxx_column_fkey} 本身就以表名
     * 开头，天然合规，不需要特殊处理。
     *
     * <p><b>不检查 RLS 策略名。</b>{@code enable_project_rls()} 给每张表建的策略一律叫
     * {@code project_isolation} —— 那是刻意的统一命名，见 V20260801_1300 的注释。
     */
    @Test
    @DisplayName("索引、约束、触发器名以所属表全名开头")
    void everyDatabaseObjectNameStartsWithItsTable() {
        // 返回「表名|类型 对象名」，表名在前是为了下面能切出来做豁免过滤。
        // left(name, length(table)) <> table 是「不以表名开头」的精确写法；
        // 用 LIKE table || '%' 不行 —— 表名里的下划线是 LIKE 的单字符通配符，会漏判
        List<String> offenders = jdbcTemplate.queryForList("""
                SELECT owner || '|' || kind || ' ' || name
                  FROM (
                        SELECT 'INDEX' AS kind, i.indexname AS name, i.tablename AS owner
                          FROM pg_indexes i
                         WHERE i.schemaname = 'public'
                        UNION ALL
                        SELECT 'CONSTRAINT', c.conname, t.relname
                          FROM pg_constraint c
                          JOIN pg_class t ON t.oid = c.conrelid
                          JOIN pg_namespace n ON n.oid = t.relnamespace
                         WHERE n.nspname = 'public'
                           AND c.contype IN ('p', 'u', 'f', 'c', 'x')
                        UNION ALL
                        SELECT 'TRIGGER', g.tgname, t.relname
                          FROM pg_trigger g
                          JOIN pg_class t ON t.oid = g.tgrelid
                          JOIN pg_namespace n ON n.oid = t.relnamespace
                         WHERE n.nspname = 'public'
                           AND NOT g.tgisinternal
                           AND g.tgname NOT IN ('ts_insert_blocker', 'ts_cagg_invalidation_trigger')
                       ) objects
                 WHERE left(name, length(owner)) <> owner
                 ORDER BY owner, name
                """, String.class).stream()
                .filter(row -> isBusinessTable(row.substring(0, row.indexOf('|'))))
                .toList();

        assertThat(offenders)
                .as("这些索引/约束/触发器名没有以所属表全名开头（ADR 0014）。索引名在 "
                        + "PostgreSQL 里是 schema 内全局唯一的，不带表名早晚撞车")
                .isEmpty();
    }

    /** 取所有需要受本规范约束的表。 */
    private List<String> businessTables() {
        return jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                 ORDER BY c.relname
                """, String.class).stream().filter(this::isBusinessTable).toList();
    }

    private boolean isBusinessTable(String table) {
        return !EXEMPT_TABLES.contains(table) && !table.endsWith(PROBE_TABLE_SUFFIX);
    }

}
