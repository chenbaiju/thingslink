package com.things.link.bootstrap.contract;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AX 接入扩展新增的库对象契约（接入合同 §7.2／§8.4；AX-6b 迁移与约束一致性）。
 *
 * <p>这些对象是四个协议共同依赖的持久事实：调试时间线的阶段列与截断标记、接入配置与会话的额外列、命令领取事实、
 * 以及"每设备只允许一个活跃 TCP 会话"的部分唯一索引。它们只由迁移创建，因此这里直接对**已迁移的库**断言，
 * 防止有人删列或改索引后仍能通过业务用例（业务用例通常只覆盖自己那条路径）。</p>
 *
 * <p>RLS 也在这里钉住：接入面的表必须对应用角色开启行级安全，否则"跨项目看不到"只能靠代码自觉。</p>
 */
class DeviceAccessSchemaContractTests extends AbstractIntegrationTest {

    /** 调试时间线在 AX-5b 新增的阶段列与标记。 */
    @Test
    void messageLogCarriesDebugTimelineColumns() throws SQLException {
        for (String column : new String[] {"message_type", "accepted_at", "parsed_at", "processed_at",
                "delivered_at", "replied_at", "truncated", "sampled"}) {
            assertThat(columnExists("ts_device_message_log", column))
                    .as("ts_device_message_log 缺少调试时间线列：%s", column).isTrue();
        }
        assertThat(indexExists("ts_device_message_log_type_ts_idx"))
                .as("按项目＋设备＋类型＋时间翻页需要组合索引").isTrue();
    }

    /** 接入面表必须存在且对应用角色启用行级安全（跨项目不可见不能只靠代码）。 */
    @Test
    void accessTablesExistWithRowLevelSecurity() throws SQLException {
        for (String table : new String[] {"dev_access_request", "dev_access_binding", "ts_device_command_claim"}) {
            assertThat(tableExists(table)).as("缺少接入面表：%s", table).isTrue();
            assertThat(rowLevelSecurityEnabled(table)).as("%s 必须启用 RLS", table).isTrue();
        }
    }

    /** 会话事实承接了代次、归属实例与最近活动：跨实例接管与在线/活动分列都依赖它们。 */
    @Test
    void sessionAndBindingCarryGenerationAndActivityFacts() throws SQLException {
        for (String column : new String[] {"generation", "owner_instance", "disconnect_reason"}) {
            assertThat(columnExists("dev_connection", column))
                    .as("dev_connection 缺少会话事实列：%s", column).isTrue();
        }
        // 最近活动属于接入配置事实（非连接型协议靠它判在线），不在会话行上。
        for (String column : new String[] {"protocol", "config_version", "enabled", "last_activity_at"}) {
            assertThat(columnExists("dev_access_binding", column))
                    .as("dev_access_binding 缺少接入配置列：%s", column).isTrue();
        }
    }

    /** 每设备一个活跃 TCP 会话由数据库仲裁，不靠应用层判断。 */
    @Test
    void onlyOneActiveTcpSessionPerDeviceIsEnforcedInDatabase() throws SQLException {
        assertThat(indexExists("dev_connection_single_active_tcp_uk"))
                .as("缺少单活跃 TCP 会话的部分唯一索引").isTrue();
    }

    /**
     * @param table 表名
     * @return 表是否存在
     * @throws SQLException 查询失败
     */
    private boolean tableExists(String table) throws SQLException {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
                + " AND table_name = ?", table) == 1;
    }

    /**
     * @param table 表名
     * @param column 列名
     * @return 列是否存在
     * @throws SQLException 查询失败
     */
    private boolean columnExists(String table, String column) throws SQLException {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = ? AND column_name = ?", table, column) == 1;
    }

    /**
     * @param index 索引名
     * @return 索引是否存在
     * @throws SQLException 查询失败
     */
    private boolean indexExists(String index) throws SQLException {
        return count("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", index) == 1;
    }

    /**
     * @param table 表名
     * @return 是否启用（并强制）行级安全
     * @throws SQLException 查询失败
     */
    private boolean rowLevelSecurityEnabled(String table) throws SQLException {
        return count("SELECT count(*) FROM pg_class WHERE relname = ? AND relrowsecurity", table) == 1;
    }

    /**
     * @param sql 计数语句
     * @param arguments 参数
     * @return 计数
     * @throws SQLException 查询失败
     */
    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }
}
