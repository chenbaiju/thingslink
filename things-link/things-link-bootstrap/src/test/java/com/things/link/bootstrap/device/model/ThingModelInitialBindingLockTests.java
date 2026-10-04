package com.things.link.bootstrap.device.model;

import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S12-P0-2a：初次版本批量绑定必须遵循拓扑多子设备操作的 PostgreSQL UUID 锁序。 */
@DisplayName("初次物模型绑定的多设备锁序")
class ThingModelInitialBindingLockTests extends AbstractIntegrationTest {
    /** 被测生产仓储，保持真实 SQL 与 INITIAL 历史写入路径。 */
    @Autowired private ThingModelVersionRepository versionRepository;
    /** 与生产仓储共用事务连接，取得 PID 后再调用实际批量绑定。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 显式事务包围实际仓储调用，保持与类型发布的同事务合同一致。 */
    @Autowired private TransactionTemplate transactions;
    /** 独占随机项目；即使夹具准备中途失败，也只清理本用例身份。 */
    private Fixture fixture;

    /**
     * 先插入高 UUID，再插入低 UUID；锁住低设备后，被测事务不得先占住高设备。
     *
     * <p>三个连接均使用 APP_ROLE。以数据库阻塞图确认发布者确实等待低设备，再由第三连接
     * NOWAIT 抢高设备锁；不把 Future 暂未完成或碰巧没有死锁当作正确排序的证据。</p>
     */
    @Test
    void locksInitialBindingDevicesInPostgresUuidOrderBeforeUpdating() throws Exception {
        fixture = seedFixture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lowHolder = appConnection(); Connection highProbe = appConnection()) {
            JdbcTemplate lowJdbc = scopedJdbc(lowHolder, fixture);
            JdbcTemplate highJdbc = scopedJdbc(highProbe, fixture);
            try {
                int lowPid = lowJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                int highPid = highJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertThat(lowPid).isNotEqualTo(highPid);
                assertThat(lowJdbc.queryForObject("""
                        SELECT id FROM dev_device WHERE project_id = ? AND id = ? FOR UPDATE
                        """, UUID.class, fixture.projectId(), fixture.lowDeviceId()))
                        .isEqualTo(fixture.lowDeviceId());

                CompletableFuture<Integer> publishingPid = new CompletableFuture<>();
                Future<UUID> publishedVersion = executor.submit(() -> {
                    // 范围必须在生产数据源借出连接前建立；不把 owner 连接注入被测仓储。
                    RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
                    try {
                        return transactions.execute(status -> {
                            jdbcTemplate.queryForObject("SELECT set_config('statement_timeout', '15000', true)",
                                    String.class);
                            // 固定一个合法的堆扫描计划，使旧版无序 UPDATE 必定先遇到物理先插入的高 UUID；
                            // 新版显式 ORDER BY 仍须先锁低 UUID，不能靠优化器偶然选了有序索引跑绿。
                            jdbcTemplate.execute("SET LOCAL enable_indexscan = off");
                            jdbcTemplate.execute("SET LOCAL enable_indexonlyscan = off");
                            jdbcTemplate.execute("SET LOCAL enable_bitmapscan = off");
                            jdbcTemplate.execute("SET LOCAL max_parallel_workers_per_gather = 0");
                            assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                                    .isEqualTo(APP_ROLE);
                            publishingPid.complete(jdbcTemplate.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class));
                            return versionRepository.createInitialFromDefinitions(
                                    fixture.tenantId(), fixture.projectId(), fixture.typeId()).id();
                        });
                    } catch (RuntimeException | Error exception) {
                        publishingPid.completeExceptionally(exception);
                        throw exception;
                    } finally {
                        RlsScopeContext.clear();
                    }
                });
                int workerPid = publishingPid.get(5, TimeUnit.SECONDS);
                assertThat(workerPid).isNotIn(lowPid, highPid);
                awaitBlockedBy(highJdbc, workerPid, lowPid, publishedVersion);

                // 已证实 B 等待 A 的低设备锁；若 B 先无序锁了高设备，这里立即报锁不可用。
                assertThat(highJdbc.queryForObject("""
                        SELECT id FROM dev_device WHERE project_id = ? AND id = ? FOR UPDATE NOWAIT
                        """, UUID.class, fixture.projectId(), fixture.highDeviceId()))
                        .isEqualTo(fixture.highDeviceId());
                highProbe.rollback();
                lowHolder.rollback();

                UUID versionId = publishedVersion.get(5, TimeUnit.SECONDS);
                // 重新设置事务级范围，从独立应用连接检查真实提交结果，而非工作线程未提交视图。
                scopedJdbc(highProbe, fixture);
                assertThat(highJdbc.queryForObject("""
                        SELECT count(*) FROM dev_device
                         WHERE project_id = ? AND thing_model_version_id = ?
                        """, Integer.class, fixture.projectId(), versionId)).isEqualTo(2);
                assertThat(highJdbc.queryForObject("""
                        SELECT count(*) FROM dev_device_model_binding_history
                         WHERE project_id = ? AND transition_type = 'INITIAL'
                           AND from_model_version_id IS NULL AND to_model_version_id = ?
                        """, Integer.class, fixture.projectId(), versionId)).isEqualTo(2);
                assertThat(highJdbc.queryForList("""
                        SELECT device_id FROM dev_device_model_binding_history
                         WHERE project_id = ? ORDER BY device_id
                        """, UUID.class, fixture.projectId()))
                        .containsExactly(fixture.lowDeviceId(), fixture.highDeviceId());
            } finally {
                // 断言失败时先释放阻塞者；工作线程的有限 statement_timeout 是最后的清理兜底。
                try {
                    highProbe.rollback();
                } finally {
                    lowHolder.rollback();
                }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS))
                    .as("发布工作线程必须退出后才允许删除夹具").isTrue();
        }
    }

    /**
     * 有界轮询真实阻塞图；短间隔仅控制探针频率，成功依据始终是指定两个物理连接之间的阻塞。
     *
     * @param observer 独立应用观察连接
     * @param waitingPid 被测事务 PID
     * @param holderPid 持有低设备行锁的 PID
     * @param operation 被测调用，提前结束不能被误认作已阻塞
     */
    private static void awaitBlockedBy(JdbcTemplate observer, int waitingPid, int holderPid,
                                       Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (Boolean.TRUE.equals(observer.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid, waitingPid))) {
                return;
            }
            assertThat(operation.isDone()).as("初次绑定不能越过低设备锁完成").isFalse();
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("初次绑定未在期限内等待指定低设备行锁");
    }

    /** @return 隔离测试容器的应用角色事务连接；不使用共享 deploy 数据库 */
    private static Connection appConnection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            connection.setAutoCommit(false);
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    /** @param connection 调用方负责回滚/关闭的物理连接 @param data 单项目夹具 @return 该连接的应用 SQL 入口 */
    private static JdbcTemplate scopedJdbc(Connection connection, Fixture data) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, data.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, data.projectId().toString());
        jdbc.queryForObject("SELECT set_config('statement_timeout', '10000', true)", String.class);
        return jdbc;
    }

    /** @return 高低 UUID 与物理插入顺序相反的独占夹具；类型/设备均尚无初始版本 */
    private Fixture seedFixture() {
        // 高位跨越 Java UUID.compareTo 的符号边界，防止误用 Java 顺序仍碰巧通过 UUIDv7 夹具。
        fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                new UUID(0x1000000000004000L, UUID.randomUUID().getLeastSignificantBits()),
                new UUID(0xf000000000004000L, UUID.randomUUID().getLeastSignificantBits()));
        JdbcTemplate owner = fixtureJdbc();
        owner.update("INSERT INTO sys_tenant (id, name) VALUES (?, '初始版本锁序租户')", fixture.tenantId());
        owner.update("""
                INSERT INTO sys_project (id, tenant_id, name, project_key)
                VALUES (?, ?, '初始版本锁序项目', ?)
                """, fixture.projectId(), fixture.tenantId(),
                "initial_lock_" + fixture.projectId().toString().replace("-", ""));
        owner.update("""
                INSERT INTO dev_type
                    (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                VALUES (?, ?, ?, 'initial_lock', '初始版本锁序类型', 'SUB_DEVICE', 'STANDARD', 'OTHER', 'DRAFT')
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
        // 先以占位 ID 插入，再按实际 ctid 选择更新目标；共享容器可能复用空闲行指针，
        // 因而不能以插入顺序推断堆扫描顺序。最终仍须验证高 UUID 物理在前。
        UUID firstPlaceholder = Uuid7.generate();
        UUID secondPlaceholder = Uuid7.generate();
        for (UUID deviceId : List.of(firstPlaceholder, secondPlaceholder)) {
            owner.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                    VALUES (?, ?, ?, ?, ?, '初始版本锁序设备')
                    """, deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(),
                    "probe_" + deviceId.toString().replace("-", ""));
        }
        List<UUID> physicalOrder = owner.queryForList("SELECT id FROM dev_device WHERE project_id = ? ORDER BY ctid",
                UUID.class, fixture.projectId());
        assertThat(physicalOrder).containsExactlyInAnyOrder(firstPlaceholder, secondPlaceholder);
        owner.update("UPDATE dev_device SET id = ? WHERE id = ? AND project_id = ?",
                fixture.highDeviceId(), physicalOrder.get(0), fixture.projectId());
        owner.update("UPDATE dev_device SET id = ? WHERE id = ? AND project_id = ?",
                fixture.lowDeviceId(), physicalOrder.get(1), fixture.projectId());
        // UPDATE 会生成新堆元组，旧页的空闲行指针可能使低 UUID 暂时排在高 UUID 前面。
        // 只重写本夹具的低设备，直到堆扫描确实先遇到高 UUID；不对共享表做 CLUSTER/VACUUM。
        for (int attempt = 0; attempt < 16; attempt++) {
            List<UUID> order = owner.queryForList(
                    "SELECT id FROM dev_device WHERE project_id = ? ORDER BY ctid",
                    UUID.class, fixture.projectId());
            if (order.equals(List.of(fixture.highDeviceId(), fixture.lowDeviceId()))) {
                break;
            }
            owner.update("UPDATE dev_device SET name = name WHERE project_id = ? AND id = ?",
                    fixture.projectId(), fixture.lowDeviceId());
        }
        // 物理顺序＝先高后低，UUID 顺序＝低在前；被测代码只能按 UUID 排序，不能依赖物理顺序。
        assertThat(owner.queryForList("SELECT id FROM dev_device WHERE project_id = ? ORDER BY ctid",
                UUID.class, fixture.projectId())).containsExactly(fixture.highDeviceId(), fixture.lowDeviceId());
        return fixture;
    }

    /** 仅删除本用例的设备/版本后再清项目与租户，保持生产 RESTRICT 外键和不可变历史规则。 */
    @AfterEach
    void cleanFixture() {
        RlsScopeContext.clear();
        if (fixture == null) {
            return;
        }
        JdbcTemplate owner = fixtureJdbc();
        owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
        owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
        owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
        owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
    }

    /** @return 只用于准备/清理本用例数据的 owner 连接，不参与被测锁序 */
    private static JdbcTemplate fixtureJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 独占项目与两台设备身份，所有验证 SQL 均限定该项目。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID typeId, UUID lowDeviceId, UUID highDeviceId) {
    }
}
