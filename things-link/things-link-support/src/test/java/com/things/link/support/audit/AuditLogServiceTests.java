package com.things.link.support.audit;

import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计日志写入与不可篡改约束。
 */
@DisplayName("审计日志")
class AuditLogServiceTests extends AbstractIntegrationTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("写入结构化审计详情")
    void writesStructuredDetails() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID actorId = Uuid7.generate();
        UUID targetId = Uuid7.generate();
        String action = "test.audit." + Uuid7.generate();

        auditLogService.record(new AuditLogEntry(
                tenantId,
                projectId,
                actorId,
                "test_target",
                targetId,
                action,
                Map.of("oldRole", "VIEWER", "newRole", "ADMIN")));

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT details ->> 'newRole'
                          FROM sys_audit_log
                         WHERE action = ?
                        """, String.class, action))
                .isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("审计日志不可 UPDATE 或 DELETE")
    void auditRowsAreImmutable() {
        UUID targetId = Uuid7.generate();
        String action = "test.audit.immutable." + Uuid7.generate();
        auditLogService.record(new AuditLogEntry(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "test_target", targetId, action, Map.of()));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_audit_log SET action = 'tampered' WHERE action = ?", action))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("sys_audit_log is immutable");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM sys_audit_log WHERE action = ?", action))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("sys_audit_log is immutable");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_audit_log WHERE action = ?",
                Integer.class, action))
                .isEqualTo(1);
    }

}
