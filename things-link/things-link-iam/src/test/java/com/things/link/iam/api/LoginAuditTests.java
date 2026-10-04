package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** G3-AUDIT-1：真实HTTP、事务回滚及PostgreSQL不可变审计。 */
@AutoConfigureMockMvc
class LoginAuditTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "a-long-private-audit-passphrase";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AuthRateLimiter limiter;
    @Autowired private PlatformTransactionManager transactions;
    private UUID account;
    private UUID tenant;
    private String email;

    @BeforeEach
    void seed() {
        limiter.clear();
        account = Uuid7.generate();
        tenant = Uuid7.generate();
        email = account + "@audit.test";
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "audit test");
        jdbc.update("""
                INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                VALUES (?,?,?,?,now())
                """, account, email, passwords.encode(PASSWORD), "audit test");
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), tenant, account);
    }

    @Test
    void successfulLoginCommitsOneAccountEventWithoutSecrets() throws Exception {
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        var event = jdbc.queryForMap("SELECT * FROM sys_audit_log WHERE actor_account_id=?", account);
        assertThat(event.get("action")).isEqualTo("iam.login.succeeded");
        assertThat(event.get("tenant_id")).isEqualTo(tenant);
        assertThat(event.get("project_id")).isNull();
        assertThat(event.get("target_id")).isEqualTo(account);
        assertThat(event.get("trace_id")).isNotNull();
        assertThat(event.get("details").toString()).isEqualTo("{}");
        assertThat(event.toString()).doesNotContain(PASSWORD, email, "Bearer", "User-Agent");
    }

    @Test
    void rejectedCredentialsPersistDespiteLoginRollback() throws Exception {
        assertThat(login(email, "wrong-password").getResponse().getStatus()).isEqualTo(401);
        var event = jdbc.queryForMap("SELECT * FROM sys_audit_log WHERE actor_account_id=?", account);
        assertThat(event.get("action")).isEqualTo("iam.login.rejected");
        assertThat(event.get("tenant_id")).isNull();
        assertThat(event.get("project_id")).isNull();
        assertThat(event.get("details").toString()).contains("20001");
        assertThat(event.toString()).doesNotContain(email, "wrong-password");
        assertThat(tokenCount()).isZero();
    }

    @Test
    void unknownAccountRejectionDoesNotPersistSubmittedIdentity() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action='iam.login.rejected' AND actor_account_id IS NULL", Long.class);
        assertThat(login("absent-" + email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE action='iam.login.rejected' AND actor_account_id IS NULL", Long.class)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE details::text LIKE ?", Long.class, "%" + email + "%")).isZero();
    }

    @Test
    void rateLimitedAttemptDoesNotCreateDatabaseAuditTraffic() throws Exception {
        int status = 0;
        for (int i = 0; i < 20 && status != 429; i++) {
            status = login(email, "wrong-password").getResponse().getStatus();
        }
        assertThat(status).isEqualTo(429);
        long before = auditCount();
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(429);
        assertThat(auditCount()).isEqualTo(before);
    }

    @Test
    void auditInsertFailureRollsBackSessionAndLoginTimestamp() throws Exception {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("""
                CREATE FUNCTION test_reject_login_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.actor_account_id='%s'::uuid THEN RAISE EXCEPTION 'audit fixture failure'; END IF;
                RETURN NEW; END $$
                """.formatted(account));
        owner.execute("CREATE TRIGGER test_reject_login_audit BEFORE INSERT ON sys_audit_log FOR EACH ROW EXECUTE FUNCTION test_reject_login_audit()");
        try {
            assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(500);
            assertThat(tokenCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT last_login_at FROM sys_account WHERE id=?", Object.class, account)).isNull();
            assertThat(auditCount()).isZero();
            assertThat(login(email, "wrong-password").getResponse().getStatus()).isEqualTo(500);
            assertThat(tokenCount()).isZero();
        } finally {
            owner.execute("DROP TRIGGER test_reject_login_audit ON sys_audit_log");
            owner.execute("DROP FUNCTION test_reject_login_audit()");
        }
    }

    @Test
    void runtimeRoleCanReadAccountEventButCannotMutateIt() throws Exception {
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE thingslink_app");
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
            assertThat(auditCount()).isEqualTo(1);
        });
        for (String sql : new String[]{"UPDATE sys_audit_log SET action='tampered' WHERE actor_account_id=?",
                "DELETE FROM sys_audit_log WHERE actor_account_id=?"}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL ROLE thingslink_app");
                jdbc.update(sql, account);
            })).hasMessageContaining("immutable");
        }
        assertThat(auditCount()).isEqualTo(1);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE actor_account_id=?", Long.class, account);
    }

    private long tokenCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sys_refresh_token WHERE account_id=?", Long.class, account);
    }

    private MvcResult login(String address, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + address + "\",\"password\":\"" + password + "\"}")).andReturn();
    }
}
