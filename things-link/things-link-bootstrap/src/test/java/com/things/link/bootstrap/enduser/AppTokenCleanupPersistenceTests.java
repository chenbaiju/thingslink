package com.things.link.bootstrap.enduser;

import com.things.link.enduser.infrastructure.persistence.JdbcAppRefreshTokenRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0144：真实迁移/运行角色/多连接锁与轮换链，不使用数据库替身。 */
class AppTokenCleanupPersistenceTests extends AbstractIntegrationTest {
    private static final Instant CUTOFF = Instant.parse("2020-01-01T00:00:00Z");
    @Autowired private JdbcAppRefreshTokenRepository repository;
    @Autowired private JdbcTemplate jdbc;
    private JdbcTemplate owner;
    private UUID tenant;
    private UUID project;
    private UUID user;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenant = Uuid7.generate(); project = Uuid7.generate(); user = Uuid7.generate();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "cleanup");
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,?,'sh-1',?)",
                project, tenant, "cleanup", project.toString().replace("-", ""));
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?,?,?,'unused','ACTIVE')",
                user, tenant, "cleanup");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
    }

    @AfterEach
    void cleanup() {
        owner.update("DELETE FROM app_refresh_token WHERE tenant_id=?", tenant);
        owner.update("DELETE FROM app_user WHERE id=?", user);
        owner.update("DELETE FROM sys_project WHERE id=?", project);
        owner.update("DELETE FROM sys_tenant WHERE id=?", tenant);
    }

    @Test
    void preservesWholeFamilyUntilEveryMemberIsStrictlyBeforeCutoff() {
        UUID family = Uuid7.generate();
        UUID ancestor = token(family, CUTOFF.minusSeconds(100));
        UUID recent = token(family, CUTOFF);
        owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?", recent, ancestor);
        UUID liveFamily = Uuid7.generate();
        token(liveFamily, CUTOFF.minusSeconds(100));
        token(liveFamily, Instant.now().plusSeconds(3600));
        assertThat(repository.deleteExpiredBefore(CUTOFF, 1000)).isZero();
        assertThat(count()).isEqualTo(4);
        assertThat(repository.deleteExpiredBefore(CUTOFF.plusSeconds(1), 1000)).isEqualTo(1);
        assertThat(count()).isEqualTo(3);
        assertThat(repository.deleteExpiredBefore(CUTOFF.plusSeconds(1), 1000)).isEqualTo(1);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void removesLongChainFromHeadWithoutForeignKeyFailure() {
        UUID family = Uuid7.generate();
        UUID first = token(family, CUTOFF.minusSeconds(1));
        UUID second = token(family, CUTOFF.minusSeconds(3)); // 故意不按过期时间的链序，不能靠排序猜前驱。
        UUID third = token(family, CUTOFF.minusSeconds(2));
        owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?", second, first);
        owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?", third, second);
        for (int remaining = 2; remaining >= 0; remaining--) {
            assertThat(repository.deleteExpiredBefore(CUTOFF, 1000)).isEqualTo(1);
            assertThat(count()).isEqualTo(remaining);
        }
        assertThat(repository.deleteExpiredBefore(CUTOFF, 1000)).isZero();
    }

    @Test
    void boundedCompetingConnectionsSkipLockedRootsAndNeverDeleteSuccessor() throws Exception {
        UUID family = Uuid7.generate();
        UUID head = token(family, CUTOFF.minusSeconds(100));
        UUID tail = token(family, CUTOFF.minusSeconds(90));
        owner.update("UPDATE app_refresh_token SET replaced_by=? WHERE id=?", tail, head);
        token(Uuid7.generate(), CUTOFF.minusSeconds(80));
        token(Uuid7.generate(), CUTOFF.minusSeconds(70));
        try (Connection a = runtimeConnection(); Connection b = runtimeConnection()) {
            var first = new JdbcAppRefreshTokenRepository(new JdbcTemplate(new SingleConnectionDataSource(a, true)));
            var second = new JdbcAppRefreshTokenRepository(new JdbcTemplate(new SingleConnectionDataSource(b, true)));
            assertThat(first.deleteExpiredBefore(CUTOFF, 1)).isEqualTo(1); // 未提交，锁住链头。
            assertThat(second.deleteExpiredBefore(CUTOFF, 1)).isEqualTo(1); // 跳锁，取别的族，不能删tail。
            b.commit(); a.commit();
        }
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_refresh_token WHERE id=?", Long.class, tail)).isEqualTo(1);
        assertThat(repository.deleteExpiredBefore(CUTOFF, 1)).isEqualTo(1);
        assertThat(repository.deleteExpiredBefore(CUTOFF, 1)).isEqualTo(1);
    }

    /** 执行计划使用嵌套循环时，锁定候选集仍只求值一次，不能突破批量上限。 */
    @Test
    void batchLimitRemainsBoundedWithNestedLoopPlan() throws Exception {
        for (int index = 0; index < 12; index++) token(Uuid7.generate(), CUTOFF.minusSeconds(100 + index));
        try (Connection connection = runtimeConnection()) {
            JdbcTemplate runtime = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            runtime.execute("SET LOCAL enable_hashjoin = off");
            runtime.execute("SET LOCAL enable_mergejoin = off");
            var cleanup = new JdbcAppRefreshTokenRepository(runtime);
            for (int remaining = 11; remaining >= 0; remaining--) {
                assertThat(cleanup.deleteExpiredBefore(CUTOFF, 1)).isEqualTo(1);
                assertThat(runtime.queryForObject("SELECT count(*) FROM app_refresh_token WHERE tenant_id=?",
                        Long.class, tenant)).isEqualTo(remaining);
            }
            assertThat(cleanup.deleteExpiredBefore(CUTOFF, 1)).isZero();
            connection.commit();
        }
    }

    @Test
    void rejectsUnsafeBoundsAndHasSupportingIndexes() {
        for (int size : new int[]{0, -1, 1001}) {
            assertThatThrownBy(() -> repository.deleteExpiredBefore(CUTOFF, size)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> repository.deleteExpiredBefore(null, 1)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('app_refresh_token_family_expiry_idx','app_refresh_token_successor_idx')", Long.class)).isEqualTo(2);
    }

    @Test
    void indexMigrationInstallsOnPopulatedPreIndexTableWithoutChangingFacts() throws Exception {
        token(Uuid7.generate(), CUTOFF.minusSeconds(1));
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var migration = getClass().getResourceAsStream("/db/migration/enduser/V20260920_0100__app_refresh_token_cleanup_indexes.sql")) {
                assertThat(migration).isNotNull();
                statement.execute("CREATE SCHEMA token_cleanup_upgrade");
                statement.execute("CREATE TABLE token_cleanup_upgrade.app_refresh_token (LIKE public.app_refresh_token INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
                statement.execute("INSERT INTO token_cleanup_upgrade.app_refresh_token SELECT * FROM public.app_refresh_token WHERE tenant_id='" + tenant + "'");
                statement.execute("SET LOCAL search_path=token_cleanup_upgrade,public");
                statement.execute(new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                try (var rows = statement.executeQuery("SELECT count(*) FROM token_cleanup_upgrade.app_refresh_token")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='token_cleanup_upgrade'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private Connection runtimeConnection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE thingslink_app");
            statement.execute("SET LOCAL statement_timeout='3s'");
            try (var result = statement.executeQuery("SELECT current_user")) {
                result.next(); assertThat(result.getString(1)).isEqualTo("thingslink_app");
            }
        }
        return connection;
    }

    private UUID token(UUID family, Instant expires) {
        UUID id = Uuid7.generate();
        owner.update("""
                INSERT INTO app_refresh_token(id,tenant_id,project_id,app_user_id,token_hash,family_id,issued_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, tenant, project, user, id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                family, Timestamp.from(expires.minusSeconds(3600)), Timestamp.from(expires));
        return id;
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM app_refresh_token WHERE tenant_id=?", Long.class, tenant);
    }
}
