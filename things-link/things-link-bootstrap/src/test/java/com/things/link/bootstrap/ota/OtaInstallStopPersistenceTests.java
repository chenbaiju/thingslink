package com.things.link.bootstrap.ota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.things.link.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 最新真实迁移的安装前停止事实及能力权限边界，不用伪造操作图替代端到端资格验证。 */
class OtaInstallStopPersistenceTests extends AbstractIntegrationTest {
    /** 完整本片事实与能力集合。 */
    private static final List<String> TABLES=List.of("ota_install_stop_cancellation","ota_install_stop_outbox","ota_install_stop_report","ota_install_stop_transport","ota_install_stop_delivery","ota_install_stop_control","ota_install_stop_status_query","ota_install_stop_candidate_control","ota_install_stop_operation");

    /** 所有新表强制RLS，普通应用具有查询权限，能力写入仍由各自守卫约束。 */
    @Test void newFactsForceRowLevelSecurity() {
        var owner=owner();
        for(String table:TABLES){
            var flags=owner.queryForMap("SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid=?::regclass",table);
            assertThat(flags).as(table).containsEntry("relrowsecurity",true).containsEntry("relforcerowsecurity",true);
            assertThat(owner.queryForObject("SELECT has_table_privilege('thingslink_app',?,'SELECT')",Boolean.class,table)).as(table).isTrue();
        }
    }
    /** 事实表零行写入仍在权限层拒绝；零行命令也不获得事实写权限。 */
    @Test void ordinaryApplicationCannotForgeFactsOrEraseGuardedCapabilities() {
        var app=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink"));
        for(String table:TABLES){
            List<String> statements = List.of("INSERT INTO " + table + " DEFAULT VALUES",
                    "UPDATE " + table + " SET tenant_id=tenant_id WHERE false",
                    "DELETE FROM " + table + " WHERE false", "TRUNCATE TABLE " + table);
            for(String statement:statements){
                if ((table.endsWith("_delivery") || table.endsWith("_transport"))
                        && (statement.startsWith("INSERT") || statement.startsWith("UPDATE"))) continue;
                assertThatThrownBy(()->app.execute(statement)).as(statement).rootCause().isInstanceOf(SQLException.class)
                        .extracting(failure->((SQLException)failure).getSQLState()).isEqualTo("42501");
            }
        }
    }
    /** owner只查看迁移产生的权限目录，不执行被测业务写入。 */
    private static JdbcTemplate owner(){return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
}
