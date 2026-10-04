package com.things.link.bootstrap.ota;

import com.things.link.ota.domain.OtaDeviceReportState;
import com.things.link.ota.infrastructure.persistence.JdbcOtaDeviceReportRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原始认证代际、序号及安全下限的真实数据库边界，不替代完整报告语法和设备认证专项。 */
class OtaDeviceReportPersistenceTests extends AbstractIntegrationTest {
    /** 仅清理本例范围。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** owner只清理隔离夹具，不放宽应用删除权限。 */
    @AfterEach void cleanup() {
        for (Fixture f : fixtures) {
            owner().update("DELETE FROM ota_device_report WHERE project_id=?", f.project());
            owner().update("DELETE FROM dev_device WHERE project_id=?", f.project());
            owner().update("DELETE FROM dev_type WHERE project_id=?", f.project());
            owner().update("DELETE FROM sys_project WHERE id=?", f.project());
            owner().update("DELETE FROM sys_tenant WHERE id=?", f.tenant());
        }
    }

    /** 同序更新不能刷新接纳时间，跨代际重新计序仍保留安全下限。 */
    @Test void fencesReplayAndPreservesCounterAcrossCredentialRotation() {
        Fixture f = seed();
        var first = state(f, 3, 9, 1, 5);
        run(f, r -> { r.create(first); return true; });
        for (var invalid : List.of(state(f, 3, 9, 2, 5), state(f, 3, 8, 2, 5),
                state(f, 2, 10, 2, 5), state(f, 4, 1, 2, 4))) {
            assertThatThrownBy(() -> run(f, r -> r.replace(1, invalid)))
                    .hasStackTraceContaining("monotone evidence rejected");
        }
        var newer = state(f, 4, 1, 2, 5);
        assertThat(OtaDeviceReportPersistenceTests.<Boolean>run(f, r -> r.replace(1, newer))).isTrue();
        assertThat(OtaDeviceReportPersistenceTests.<Boolean>run(f, r -> r.replace(1, state(f, 4, 2, 3, 5)))).isFalse();
        var actual = run(f, r -> r.find(f.project(), f.device(), false, false).orElseThrow());
        assertThat(actual.credentialVersion()).isEqualTo(4);
        assertThat(actual.reportSequence()).isEqualTo(1);
        assertThat(actual.brokerReceivedAt()).isEqualTo(newer.brokerReceivedAt());
        var next = state(f, 4, 2, 3, 5);
        var timeRegression = new OtaDeviceReportState(f.tenant(), f.project(), f.device(), 4, 2, 3, 5,
                next.canonical(), next.reportHash(), next.brokerReceivedAt(), first.acceptedAt());
        assertThatThrownBy(() -> run(f, r -> r.replace(2, timeRegression)))
                .hasStackTraceContaining("monotone evidence rejected");
        assertThatThrownBy(() -> app(f, j -> j.update(
                "UPDATE ota_device_report SET device_id=gen_random_uuid() WHERE device_id=?", f.device())))
                .hasStackTraceContaining("monotone evidence rejected");
        byte[] copy = actual.canonical();
        copy[0] = 0;
        assertThat(actual.canonical()).isEqualTo(newer.canonical());
    }

    /** 数据库自身检查字节摘要、持久字段映射及设备范围外键。 */
    @Test void rejectsCorruptCanonicalAndScopeSubstitution() {
        Fixture f = seed();
        var valid = state(f, 0, 1, 1, 0);
        var mismatched = new OtaDeviceReportState(f.tenant(), f.project(), f.device(), 0, 2, 1, 0,
                valid.canonical(), valid.reportHash(), valid.brokerReceivedAt(), valid.acceptedAt());
        assertThatThrownBy(() -> run(f, r -> { r.create(mismatched); return true; }))
                .hasStackTraceContaining("canonical fields mismatch");
        var wrongHash = new OtaDeviceReportState(f.tenant(), f.project(), f.device(), 0, 1, 1, 0,
                valid.canonical(), "b".repeat(64), valid.brokerReceivedAt(), valid.acceptedAt());
        assertThatThrownBy(() -> run(f, r -> { r.create(wrongHash); return true; }))
                .hasStackTraceContaining("ota_device_report_hash_ck");
        Fixture other = seed();
        for (UUID device : List.of(Uuid7.generate(), other.device())) {
            Fixture wrong = new Fixture(f.tenant(), f.project(), f.type(), device, f.time());
            assertThatThrownBy(() -> run(f, r -> { r.create(state(wrong, 0, 1, 1, 0)); return true; }))
                    .hasStackTraceContaining("ota_device_report_device_fk");
        }
        run(f, r -> { r.create(valid); return true; });
        assertThat(OtaDeviceReportPersistenceTests.<java.util.Optional<OtaDeviceReportState>>run(other,
                r -> r.find(f.project(), f.device(), false, false))).isEmpty();
        assertThatThrownBy(() -> app(f, j -> j.update("DELETE FROM ota_device_report WHERE device_id=?", f.device())))
                .hasStackTraceContaining("permission denied");
    }

    /** 共享资格锁持续到事务结束，不能在读取后悄悄切换报告。 */
    @Test void sharedReadBlocksConcurrentReplacement() throws Exception {
        Fixture f = seed();
        run(f, r -> { r.create(state(f, 0, 1, 1, 0)); return true; });
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reader = pool.submit(() -> run(f, r -> {
                r.find(f.project(), f.device(), false, true).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("测试锁未释放");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> app(f, j -> {
                    j.execute("SET LOCAL lock_timeout='150ms'");
                    return new JdbcOtaDeviceReportRepository(j).replace(1, state(f, 0, 2, 2, 0));
                })).hasStackTraceContaining("lock timeout");
            } finally {
                release.countDown();
            }
            assertThat(reader.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(true);
        }
    }

    /** 项目报告清理每批至多500条，错误token不能删除另一批。 */
    @Test void cleansFiveHundredReportsWithCurrentProjectLease() {
        Fixture f = seed();
        var devices = new ArrayList<UUID>();
        devices.add(f.device());
        for (int i = 1; i < 501; i++) {
            UUID device = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES (?,?,?,?,?,'报告设备')", device, f.tenant(), f.project(), f.type(), "d_" + i);
            devices.add(device);
        }
        app(f, j -> {
            var repository = new JdbcOtaDeviceReportRepository(j);
            for (UUID device : devices) {
                repository.create(state(new Fixture(f.tenant(), f.project(), f.type(), device, f.time()), 0, 1, 1, 0));
            }
            return true;
        });
        UUID token = Uuid7.generate();
        owner().update("UPDATE sys_project SET status='PURGING',lifecycle_generation=1,deleted_at=now()-interval '31 days',"
                + "cleanup_stage='OTA',cleanup_started_at=now(),cleanup_next_attempt_at=now(),cleanup_lease_token=?,"
                + "cleanup_lease_until=now()+interval '120 seconds' WHERE id=?", token, f.project());
        assertThatThrownBy(() -> batch(f, Uuid7.generate())).hasStackTraceContaining("authorization rejected");
        assertThat(batch(f, token)).isEqualTo(500);
        assertThat(batch(f, token)).isEqualTo(1);
        assertThat(batch(f, token)).isZero();
    }

    /** 普通角色调用受限清理函数。 */
    private int batch(Fixture f, UUID token) {
        return plain(j -> j.queryForObject("SELECT deleted_rows FROM ota_project_cleanup_batch(?,?,1,?)",
                Integer.class, f.tenant(), f.project(), token));
    }
    /** owner只构造真实设备父图。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        fixtures.add(f);
        owner().update("INSERT INTO sys_tenant(id,name) VALUES (?,'报告租户')", f.tenant());
        owner().update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'报告项目',?)",
                f.project(), f.tenant(), "report_" + f.project().toString().replace("-", ""));
        owner().update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol)"
                + " VALUES (?,?,?,'report-type','报告类型','DIRECT','STANDARD')", f.type(), f.tenant(), f.project());
        owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                + " VALUES (?,?,?,?,'first','报告设备')", f.device(), f.tenant(), f.project(), f.type());
        return f;
    }
    /** 仅持久字段子集；完整报告合同另行测试。 */
    private static OtaDeviceReportState state(Fixture f, long generation, long sequence, long revision, long committed) {
        byte[] bytes = ("{\"committedSecurityVersion\":" + committed + ",\"reportSequence\":" + sequence + "}")
                .getBytes(StandardCharsets.UTF_8);
        try {
            return new OtaDeviceReportState(f.tenant(), f.project(), f.device(), generation, sequence, revision, committed,
                    bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    f.time(), f.time().plusSeconds(revision));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    /** 原事务普通角色仓储。 */
    private static <T> T run(Fixture f, Function<JdbcOtaDeviceReportRepository, T> work) {
        return app(f, j -> work.apply(new JdbcOtaDeviceReportRepository(j)));
    }
    /** 每次显式设置完整RLS，不借用连接残留。 */
    private static <T> T app(Fixture f, Function<JdbcTemplate, T> work) {
        return plain(j -> {
            j.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
            j.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
            return work.apply(j);
        });
    }
    /** 使用真实应用事务与权限。 */
    private static <T> T plain(Function<JdbcTemplate, T> work) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s -> work.apply(new JdbcTemplate(source)));
    }
    /** owner仅夹具和独立观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
    /** 隔离父图及固定原始接收时间。 */
    private record Fixture(UUID tenant, UUID project, UUID type, UUID device, Instant time) { }
}
