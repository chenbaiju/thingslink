package com.things.link.bootstrap.telemetry.overview;

import com.things.link.device.application.DeviceCurrentValueService;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceCurrentValueCache.ValueKey;
import com.things.link.ingestion.application.RealtimeKafkaPublisher;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** D-141：真实事务提交次序与延迟缓存回调交错，不能让热副本回退到旧PG事实。 */
@Import(DeviceReportedOrderingIntegrationTests.IsolatedConfiguration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceReportedOrderingIntegrationTests extends AbstractIntegrationTest {
    /** 专属PG隔离所有影子版本与模型绑定事实。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reported_ordering").withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 先启动以提供普通角色及Flyway统一连接。 */
    private static final String DATABASE_URL = startDatabase();
    /** 设备时间采用微秒刻度，两个反例均落同一毫秒。 */
    private static final Instant TIME = Instant.parse("2026-09-12T00:00:00.123100Z");
    /** 被测摄入服务保留真实事务、模型核验与提交回调。 */
    @Autowired private DeviceIngestionService ingestion;
    /** 显式回滚真实外层事务以验证接受序号与缓存回调共同撤销。 */
    @Autowired private TransactionTemplate transaction;
    /** 真实模型升级入口用于未更新属性来源保持反例。 */
    @Autowired private ThingModelVersionBindingService bindings;
    /** 冷读和回填仍调用真实服务。 */
    @Autowired private DeviceCurrentValueService current;
    /** spy仅暂停旧回调，所有Redis合并仍调用原实现。 */
    @MockitoSpyBean private DeviceCurrentValueCache cache;
    /** 本例只验证 PG 与 Redis 顺序，避免向未启动的 Kafka 提交异步在线增量。 */
    @MockitoBean private RealtimeKafkaPublisher realtimeKafkaPublisher;
    /** 普通APP角色连线取证。 */
    @Autowired private JdbcTemplate jdbc;
    /** 仅清理本例精确缓存键。 */
    @Autowired private StringRedisTemplate redis;
    /** 禁止共享配额初始化器误连其他库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuota;
    /** 本例租户。 */
    private UUID tenant;
    /** 本例项目。 */
    private UUID project;
    /** 本例设备。 */
    private UUID device;
    /** 本例共享Redis中的唯一键。 */
    private String redisKey;

    /** 真实模型版本绑定与数据库种子，不伪造受信上下文。 */
    @BeforeEach
    void seed() throws SQLException {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE.getDatabaseName());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        tenant = Uuid7.generate();
        project = Uuid7.generate();
        device = Uuid7.generate();
        UUID type = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?,'上报排序')", tenant);
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'上报排序','sh-1',?)", project, tenant, "ordering_" + project.toString().replace("-", ""));
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES (?,?,?,'ordering','排序','STANDARD','DIRECT')", type, tenant, project);
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'ordering','排序','ONLINE')", device, tenant, project, type);
        }
        seedThingModelVersion(tenant, project, type, device,
                "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}");
        redisKey = "things-link:shadow:v2:{" + project + "}:" + device + ":value";
    }

    /** A已提交但回调阻塞，B提交并回调后释放A；同时间/同毫秒较早微秒均不能回退。 */
    @ParameterizedTest
    @ValueSource(longs = {0, 100_000})
    void lateAfterCommitCannotReplaceLaterDatabaseFact(long newerNanos) throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseOldMerge(paused, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> write(1, TIME));
            assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
            assertDatabase(1);
            write(2, TIME.plusNanos(newerNanos));
            assertDatabase(2);
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            assertDatabase(2);
            assertCache(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 冷读已取得A后暂停回填，真实B提交不能被随后执行的旧回填覆盖。 */
    @Test
    void staleColdReadBackfillCannotReplaceCommittedNewValue() throws Exception {
        write(1, TIME);
        redis.delete(redisKey);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseOldMerge(paused, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var cold = executor.submit(() -> {
                TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
                try {
                    return current.findAllTrusted(project, List.of(device), List.of("value"));
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
            write(2, TIME);
            assertDatabase(2);
            release.countDown();
            assertThat(cold.get(15, TimeUnit.SECONDS)).singleElement()
                    .satisfies(value -> assertThat(value.value().asInt()).isEqualTo(1));
            assertDatabase(2);
            assertCache(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 事务回滚不消耗接受序号，也不能执行提交缓存回调。 */
    @Test
    void rollbackDoesNotAdvanceReportedRevision() throws Exception {
        write(1, TIME);
        long before = sequence();
        transaction.executeWithoutResult(status -> {
            write(2, TIME);
            status.setRollbackOnly();
        });
        assertThat(sequence()).isEqualTo(before);
        assertDatabase(1);
        assertCache(1);
    }

    /** bigint耗尽必须失败回滚，不能把异常当迟到拒绝或成功确认。 */
    @Test
    void overflowRollsBackWithoutPublishingNewCacheValue() throws Exception {
        write(1, TIME);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE dev_shadow SET reported_sequence=9223372036854775807 WHERE device_id=?", device);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> write(2, TIME))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(sequence()).isEqualTo(Long.MAX_VALUE);
        assertDatabase(1);
        assertCache(1);
    }

    /** 超JS安全整数的真实PG序号仍以字符串精确进入Redis。 */
    @Test
    void preservesLargeDatabaseAcceptedRevision() throws Exception {
        write(1, TIME);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE dev_shadow SET reported_sequence=9007199254740992 WHERE device_id=?", device);
        }
        write(2, TIME);
        ValueKey key = new ValueKey(device, "value");
        assertThat(cache.findAll(project, List.of(key)).get(key).reportedRevision()).isEqualTo("9007199254740993");
        assertThat(sequence()).isEqualTo(9007199254740993L);
    }

    /** 缓存淘汰丢失水位后A可写入空键，但公共读取必须核对PG序号并返回B。 */
    @Test
    void evictedWatermarkCannotMakeResurrectedOldCallbackAuthoritative() throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseOldMerge(paused, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> write(1, TIME));
            assertThat(paused.await(15, TimeUnit.SECONDS)).isTrue();
            write(2, TIME);
            redis.delete(redisKey);
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            assertCache(1);
            TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
            var values = current.findAllTrusted(project, List.of(device), List.of("value"));
            assertThat(values).singleElement().satisfies(value -> {
                assertThat(value.value().asInt()).isEqualTo(2);
                assertThat(value.reportedRevision()).isEqualTo("2");
            });
            assertCache(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            TenantContext.clear();
        }
    }

    /** 真实升级设备绑定后，未更新属性仍保留写入时模型UUID和原接受序号。 */
    @Test
    void modelUpgradeDoesNotRewriteUnchangedPropertySource() throws Exception {
        write(1, TIME);
        ValueKey key = new ValueKey(device, "value");
        UUID original = cache.findAll(project, List.of(key)).get(key).thingModelVersionId();
        UUID upgraded = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, """
                    INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,
                        version_number,version_major,version_minor,version_patch,change_level,schema_profile,
                        model_snapshot,schema_digest,digest_algorithm)
                    SELECT ?,tenant_id,project_id,device_type_id,'2.0.0',2,0,0,'MAJOR',schema_profile,
                           model_snapshot,schema_digest,digest_algorithm
                      FROM dev_thing_model_version WHERE id=?
                    """, upgraded, original);
        }
        TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
        bindings.bind(project, device, upgraded, Uuid7.generate(),
                com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType.UPGRADE,
                Instant.now());
        redis.delete(redisKey);
        assertThat(current.findAllTrusted(project, List.of(device), List.of("value")))
                .singleElement().satisfies(value -> {
                    assertThat(value.thingModelVersionId()).isEqualTo(original);
                    assertThat(value.reportedRevision()).isEqualTo("1");
                });
    }

    /** 旧行默认未知序号，迁移约束拒绝非规范、非字符串及越界水位。 */
    @Test
    void migrationKeepsLegacyOrderUnknownAndRejectsMalformedRevisions() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, """
                    INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at)
                    VALUES (?,?,?,'{"value":1}'::jsonb,'{"value":"2026-09-12T00:00:00.123100Z"}'::jsonb)
                    """, device, tenant, project);
            for (String invalid : List.of("{\"value\":0}", "{\"value\":\"0\"}",
                    "{\"value\":\"01\"}", "{\"value\":\"9223372036854775808\"}", "[]")) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute(owner,
                        "UPDATE dev_shadow SET reported_revisions=?::jsonb WHERE device_id=?", invalid, device))
                        .isInstanceOf(SQLException.class);
            }
        }
        assertThat(sequence()).isZero();
        TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
        assertThat(current.findAllTrusted(project, List.of(device), List.of("value")))
                .singleElement().satisfies(value -> {
                    assertThat(value.value().asInt()).isEqualTo(1);
                    assertThat(value.reportedRevision()).isNull();
                    assertThat(value.thingModelVersionId()).isNull();
                });
        assertThat(redis.hasKey(redisKey)).isFalse();
    }

    /** 热缓存虽含原项目值，错误RLS范围仍不能凭原项目查询参数读取它。 */
    @Test
    void wrongRlsScopeCannotExposeOriginalProjectHotCache() {
        write(1, TIME);
        assertCache(1);
        TenantContext.set(new TenantScope(tenant, Uuid7.generate(), Uuid7.generate()));
        assertThat(current.findAllTrusted(project, List.of(device), List.of("value"))).isEmpty();
        // 没有清缓存制造未命中；原缓存事实仍在，拒绝必须来自PG资格核验。
        assertCache(1);
    }

    /** 独立读取设备接受计数，不将desired版本误作reported顺序。 */
    private long sequence() throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); var query = owner.prepareStatement(
                "SELECT reported_sequence FROM dev_shadow WHERE device_id=?")) {
            query.setObject(1, device);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 仅拦截A值一次；调用原Redis方法，不能用人工缓存写冒充提交链。 */
    private void pauseOldMerge(CountDownLatch paused, CountDownLatch release) {
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            Map<String, DeviceCurrentValueCache.ReportedValue> values = call.getArgument(2);
            if ("1".equals(values.get("value").jsonValue()) && once.compareAndSet(false, true)) {
                paused.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(cache).merge(eq(project), eq(device), anyMap());
    }

    /** 每次通过生产资格解析及事务代理，回调阻塞时owner查询可独立证明已经提交。 */
    private void write(int value, Instant occurredAt) {
        TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
        try {
            Map<String, Object> properties = Map.of("value", value);
            var context = ingestion.validateReportedProperties(tenant, project, device, "1.0.0", Instant.now(), properties);
            ingestion.mergeReportedProperties(context, project, device, properties, occurredAt, Uuid7.generate(), "reported-ordering");
        } finally {
            TenantContext.clear();
        }
    }

    /** 独立owner连接只读取物理PG当前值，不借缓存或未提交事务判成功。 */
    private void assertDatabase(int expected) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); var query = owner.prepareStatement("SELECT reported->>'value' FROM dev_shadow WHERE device_id=?")) {
            query.setObject(1, device);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(Integer.toString(expected));
            }
        }
    }

    /** 最终值直接从真实缓存读取，不允许PG回源掩盖热副本错误。 */
    private void assertCache(int expected) {
        ValueKey key = new ValueKey(device, "value");
        assertThat(cache.findAll(project, List.of(key)).get(key).value().asInt()).isEqualTo(expected);
    }

    /** 先结束用例线程再精确清共享Redis键及线程范围。 */
    @AfterEach
    void cleanup() {
        try {
            if (redisKey != null) redis.delete(redisKey);
        } finally {
            TenantContext.clear();
        }
    }

    /** 专库owner连接仅用于模型/设备种子与提交证据。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, DATABASE.getUsername(), DATABASE.getPassword());
    }

    /** 参数化固定种子SQL，保持所有业务守卫。 */
    private static void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (var statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    /** 提供实际随机端口后才构建上下文。 */
    private static String startDatabase() {
        DATABASE.start();
        return DATABASE.getJdbcUrl();
    }

    /** 只覆盖专库连线，缓存及业务端口保持真实。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedConfiguration {
        /** 普通角色与Flyway保持同一专库。 */
        @Bean
        DynamicPropertyRegistrar properties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }
}
