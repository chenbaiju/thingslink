package com.things.link.bootstrap.telemetry.overview;

import com.things.link.device.application.DeviceCurrentValueService;
import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** D-146：真实PG原文经当前值受信核心回填Redis，纯热命中仍保持嵌套数值精度。 */
@Import(DeviceCurrentValuePrecisionIntegrationTests.IsolatedConfiguration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceCurrentValuePrecisionIntegrationTests extends AbstractIntegrationTest {
    /** 影子事实使用专库，普通应用连接与owner种子必须指向同一数据库。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("current_value_precision")
            .withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 在Spring配置注册前取得真实端口，类末由所有权监听器回收。 */
    private static final String DATABASE_URL = startDatabase();
    /** 从原文字面量构造，避免夹具先经Double损失。 */
    private static final String VALUE = """
            {"decimal":0.12345678901234567890123456789,
             "integer":9223372036854775808123456789,
             "list":[0.12345678901234567890123456789,{"integer":9223372036854775808123456789}]}
            """;
    /** 历史毫秒缓存协议不在精度片改动，取整数秒使时间断言明确。 */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-12T00:00:00Z");
    /** 禁止共享库配额初始化器影响本类专库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 被测原生产服务；本片不把受信核心调用当HTTP或App绑定授权资格。 */
    @Autowired
    private DeviceCurrentValueService service;
    /** 仅观察和禁止第二次回源，第一次实际执行原仓储SQL。 */
    @MockitoSpyBean
    private DeviceShadowRepository shadows;
    /** 普通APP角色连线身份的独立断言。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 观察真实Redis存储原文，并仅清理本例精确键。 */
    @Autowired
    private StringRedisTemplate redis;
    /** 失败时也清理已登记的本例随机项目缓存键。 */
    private final List<String> cacheKeys = new ArrayList<>();

    /** PG冷读、真实回填及禁止回源的热读必须保持相同JSON数值；另验证异项目键隔离。 */
    @Test
    void preservesOriginalJsonThroughDatabaseBackfillAndCacheOnlyRead() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE.getDatabaseName());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject(
                "SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class)).isTrue();
        UUID tenant = Uuid7.generate(), project = Uuid7.generate(), type = Uuid7.generate(), device = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?,'当前值精度')", tenant);
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'当前值精度','sh-1','current_precision')", project, tenant);
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES (?,?,?,'precision','精度','STANDARD','DIRECT')", type, tenant, project);
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'precision','精度','ONLINE')", device, tenant, project, type);
            execute(owner, "INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,version,reported_sequence,reported_revisions) VALUES (?,?,?,?::jsonb,?::jsonb,7,7,'{\"payload\":\"7\"}'::jsonb)",
                    device, tenant, project, "{\"payload\":" + VALUE + "}", "{\"payload\":\"" + OCCURRED_AT + "\"}");
        }
        String key = "things-link:shadow:v2:{" + project + "}:" + device + ":payload";
        cacheKeys.add(key);
        assertThat(redis.hasKey(key)).isFalse();
        TenantContext.set(new TenantScope(tenant, project, Uuid7.generate()));
        List<DeviceCurrentValue> cold = service.findAllTrusted(project, List.of(device), List.of("payload"));
        assertCurrentValue(cold, device);
        verify(shadows, times(1)).findSnapshots(eq(project), anyCollection());
        String stored = redis.opsForValue().get(key);
        assertThat(stored).contains("0.12345678901234567890123456789", "9223372036854775808123456789");
        assertThat(redis.getExpire(key)).isPositive();

        // 回源一旦发生直接失败，不能让第二次PG冷读掩盖缓存解码失真。
        doThrow(new AssertionError("第二次读取必须完全命中热缓存"))
                .when(shadows).findSnapshots(eq(project), anyCollection());
        List<DeviceCurrentValue> hot = service.findAllTrusted(project, List.of(device), List.of("payload"));
        assertCurrentValue(hot, device);
        verify(shadows, times(1)).findSnapshots(eq(project), anyCollection());
        assertThat(hot).isEqualTo(cold);

        UUID otherProject = Uuid7.generate();
        TenantContext.set(new TenantScope(tenant, otherProject, Uuid7.generate()));
        assertThat(service.findAllTrusted(otherProject, List.of(device), List.of("payload"))).isEmpty();
    }

    /** 独立字面期望同时验证JSON类型和完整嵌套数值，不使用被测mapper生成期望。 */
    private static void assertCurrentValue(List<DeviceCurrentValue> values, UUID device) {
        assertThat(values).hasSize(1);
        DeviceCurrentValue value = values.getFirst();
        assertThat(value.deviceId()).isEqualTo(device);
        assertThat(value.propertyKey()).isEqualTo("payload");
        assertThat(value.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(value.shadowVersion()).isEqualTo(7);
        assertThat(value.reportedRevision()).isEqualTo("7");
        JsonNode json = value.value();
        assertDecimal(json.get("decimal"));
        assertDecimal(json.get("list").get(0));
        assertThat(json.get("integer").isIntegralNumber()).isTrue();
        assertThat(json.get("integer").asText()).isEqualTo("9223372036854775808123456789");
        assertThat(json.get("list").get(1).get("integer")).isEqualTo(json.get("integer"));
    }

    /** 数学值不随JSON合法缩写或空白变化，但字符串不能冒充数值。 */
    private static void assertDecimal(JsonNode value) {
        assertThat(value.isNumber()).isTrue();
        assertThat(new BigDecimal(value.asText())).isEqualByComparingTo("0.12345678901234567890123456789");
    }

    /** owner种子连接固定为专库，不依赖当前线程RLS范围。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, DATABASE.getUsername(), DATABASE.getPassword());
    }

    /** 仅参数化固定SQL写入本例种子。 */
    private static void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (var statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    /** 只删精确随机键，不清空共享Redis；任何失败都清除线程范围。 */
    @AfterEach
    void clearOwnedCache() {
        try {
            if (!cacheKeys.isEmpty()) redis.delete(cacheKeys);
        } finally {
            TenantContext.clear();
        }
    }

    /** 先启动专库以注册真实随机端口。 */
    private static String startDatabase() {
        DATABASE.start();
        return DATABASE.getJdbcUrl();
    }

    /** 专库只替换数据源，不改变原缓存/服务/仓储实现。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedConfiguration {
        /** 数据源与Flyway必须同时指向专库。 */
        @Bean
        DynamicPropertyRegistrar properties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }
}
