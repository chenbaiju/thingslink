package com.things.link.bootstrap.assistant.fixture;

import com.things.link.ThingsLinkApplication;
import com.things.link.assistant.application.AnalysisTransport;
import com.things.link.assistant.application.FrozenSyntheticReview;
import com.things.link.assistant.application.SyntheticReviewedTransport;
import com.things.link.assistant.infrastructure.transport.AnalysisTransportConfiguration;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.support.tenant.TenantAwareDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 测试白名单类路径的独立进程；不创建种子或公开入口，不可用作生产启动入口。 */
public final class SyntheticReviewedFixtureProcess {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static FixtureManifest fixture;

    private SyntheticReviewedFixtureProcess() {}

    /** 只有公开身份和独立库名称，不包含凭据、正文或生产配置。 */
    record FixtureManifest(String database, String appRole, List<SyntheticReviewedTransport.Target> targets) {}

    /**
     * 在根事先迁移和准备新独占库后启动；只允许固定端口及摘要绑定的公开材料。
     * @param args 必须为空，私有配置只由受控环境或系统属性提供，禁止命令行覆盖装配守卫
     */
    public static void main(String[] args) {
        byte[] raw = null;
        try {
            require(args != null && args.length == 0);
            System.setProperty("socksProxyHost", "");
            System.setProperty("http.proxyHost", "");
            System.setProperty("https.proxyHost", "");
            Path logs = Path.of(System.getProperty("tc.010c.logs")).toRealPath();
            require(logs.toString().endsWith("/things-link-console/logs"));
            Path path = Path.of(System.getProperty("tc.010c.fixture")).toRealPath();
            require(path.startsWith(logs) && Files.size(path) <= MAX_MANIFEST_BYTES);
            raw = Files.readAllBytes(path);
            fixture = parseManifest(raw, System.getProperty("tc.010c.fixtureSha256"));
            require(jdbcUrl(fixture).equals(System.getenv("SPRING_DATASOURCE_URL"))
                    && "8089".equals(System.getenv("SERVER_PORT"))
                    && "127.0.0.1".equals(System.getenv("SERVER_ADDRESS")));
            // 默认不装配，避免完整测试类路径扫描时影响其他集成测试。
            System.setProperty("tc.010c.fixture-enabled", "true");
            new SpringApplicationBuilder(ThingsLinkApplication.class)
                    .sources(FixtureConfiguration.class).profiles("test").run(args);
        } catch (Exception ignored) {
            throw invalid();
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    /** 纯解析入口便于独立验证，不启动应用、不访问数据库。 */
    static FixtureManifest parseManifest(byte[] raw, String expectedSha256) {
        try {
            require(raw != null && raw.length > 0 && raw.length <= MAX_MANIFEST_BYTES
                    && expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}")
                    && expectedSha256.equals(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(raw))));
            JsonNode document = JSON.readTree(raw);
            require(document.isObject() && document.propertyNames().equals(Set.of(
                    "qualification", "database", "appRole", "apiPort", "uiPort", "targets"))
                    && "010-C-SYNTHETIC-REVIEWED-INTERACTION".equals(text(document, "qualification")));
            String database = text(document, "database"), appRole = text(document, "appRole");
            require(database.matches("tc_console_010c_[A-Za-z0-9_]+")
                    && database.length() <= 63 && appRole.matches("[a-z_][a-z0-9_]{0,62}")
                    && port(document, "apiPort", 8089) && port(document, "uiPort", 3018));
            JsonNode values = document.path("targets");
            require(values.isArray() && values.size() >= 1 && values.size() <= 2);
            var targets = new ArrayList<SyntheticReviewedTransport.Target>();
            for (var target : values) {
                require(target.isObject() && target.propertyNames().equals(Set.of(
                        "tenantId", "projectId", "actors", "deviceIds", "unknownDeviceIds")));
                var actors = new HashMap<String, UUID>();
                require(target.path("actors").isObject());
                for (String role : target.path("actors").propertyNames())
                    actors.put(role, uuid(text(target.path("actors"), role)));
                targets.add(new SyntheticReviewedTransport.Target(uuid(text(target, "tenantId")),
                        uuid(text(target, "projectId")), actors, ids(target.path("deviceIds")),
                        ids(target.path("unknownDeviceIds"))));
            }
            require(targets.stream().map(SyntheticReviewedTransport.Target::projectId)
                    .distinct().count() == targets.size());
            return new FixtureManifest(database, appRole, List.copyOf(targets));
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    /** 只有显式测试入口才能打开此装配，发布包没有此测试类。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "tc.010c.fixture-enabled", havingValue = "true")
    public static class FixtureConfiguration {
        /**
         * 验证两类真实连接池及受控身份，只读检查不代替每次公开请求的最终确权。
         * @param jdbc 真实应用的租户感知数据源
         * @param production 原生产端口必须完全关闭，不接触模型凭据
         * @param environment 最终生效的监听与数据库配置
         * @return 固定期限、无网络的合成测试端口
         */
        @Bean
        @Primary
        public AnalysisTransport syntheticReviewedTransport(JdbcTemplate jdbc,
                AnalysisTransportConfiguration.Properties production, Environment environment) {
            require(fixture != null && jdbc.getDataSource() instanceof TenantAwareDataSource
                    && Integer.valueOf(8089).equals(environment.getProperty("server.port", Integer.class))
                    && "127.0.0.1".equals(environment.getProperty("server.address"))
                    && jdbcUrl(fixture).equals(environment.getProperty("spring.datasource.url"))
                    && production.getOrigin() == null && production.getIdentityFile() == null
                    && production.getTrustFile() == null && production.getIdentityPassword() == null
                    && production.getTrustPassword() == null && production.getCounterSha256() == null
                    && production.getExecutionCandidateSha256() == null);
            for (var workload : DatabaseWorkload.values()) {
                try (var scope = DatabaseWorkloadContext.enter(workload)) {
                    require(fixture.database().equals(jdbc.queryForObject("SELECT current_database()", String.class))
                            && fixture.appRole().equals(jdbc.queryForObject("SELECT current_user", String.class))
                            && Boolean.TRUE.equals(jdbc.queryForObject("""
                                    SELECT NOT rolsuper AND NOT rolbypassrls
                                      FROM pg_roles WHERE rolname=current_user
                                    """, Boolean.class)));
                }
            }
            require(Set.copyOf(jdbc.queryForList("SELECT id FROM sys_project", UUID.class)).equals(
                    fixture.targets().stream().map(SyntheticReviewedTransport.Target::projectId)
                            .collect(Collectors.toSet())));
            for (var target : fixture.targets()) {
                TenantContext.set(new TenantScope(target.tenantId(), target.projectId(), target.actors().get("OWNER")));
                try {
                    require(jdbc.queryForObject("""
                            SELECT count(*) FROM sys_project WHERE id=? AND tenant_id=? AND status='ACTIVE'
                            """, Integer.class, target.projectId(), target.tenantId()) == 1);
                    require(jdbc.queryForObject("SELECT count(*) FROM sys_project_member WHERE project_id=?",
                            Integer.class, target.projectId()) == 4);
                    for (var actor : target.actors().entrySet()) {
                        require(jdbc.queryForObject("""
                                SELECT count(*) FROM sys_project_member
                                 WHERE project_id=? AND account_id=? AND role=? AND status='ACTIVE'
                                """, Integer.class, target.projectId(), actor.getValue(), actor.getKey()) == 1);
                        require(jdbc.queryForObject("""
                                SELECT count(*) FROM sys_account a
                                 WHERE a.id=? AND a.status='ACTIVE' AND a.deleted_at IS NULL
                                   AND a.email_verified_at IS NOT NULL
                                   AND EXISTS(SELECT 1 FROM sys_tenant_member m
                                                JOIN sys_tenant t ON t.id=m.tenant_id
                                               WHERE m.account_id=a.id AND m.status='ACTIVE'
                                                 AND t.status='ACTIVE' AND t.deleted_at IS NULL)
                                """, Integer.class, actor.getValue()) == 1);
                    }
                    require(jdbc.queryForObject("""
                            SELECT count(*) FROM assistant_model_configuration
                             WHERE tenant_id=? AND project_id=? AND purpose='deepseek-chat'
                               AND enabled=true AND revision>0
                            """, Integer.class, target.tenantId(), target.projectId()) == 1);
                    for (UUID device : target.deviceIds()) require(jdbc.queryForObject("""
                            SELECT count(*) FROM dev_device
                             WHERE id=? AND tenant_id=? AND project_id=? AND deleted_at IS NULL
                            """, Integer.class, device, target.tenantId(), target.projectId()) == 1);
                } finally {
                    TenantContext.clear();
                }
            }
            return new SyntheticReviewedTransport(FrozenSyntheticReview.createOnce(Instant.now()), fixture.targets());
        }
    }

    private static String jdbcUrl(FixtureManifest value) {
        return "jdbc:postgresql://localhost:5548/" + value.database();
    }
    private static String text(JsonNode parent, String field) {
        require(parent.path(field).isString());
        return parent.path(field).asString();
    }
    private static boolean port(JsonNode parent, String field, int expected) {
        JsonNode value = parent.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() == expected;
    }
    private static Set<UUID> ids(JsonNode values) {
        require(values.isArray());
        var result = new HashSet<UUID>();
        for (var value : values) require(value.isString() && result.add(uuid(value.asString())));
        return Set.copyOf(result);
    }
    private static UUID uuid(String value) {
        UUID result = UUID.fromString(value);
        require(result.toString().equals(value));
        return result;
    }
    private static void require(boolean value) { if (!value) throw invalid(); }
    private static IllegalStateException invalid() { return new IllegalStateException("INVALID_SYNTHETIC_FIXTURE"); }
}
