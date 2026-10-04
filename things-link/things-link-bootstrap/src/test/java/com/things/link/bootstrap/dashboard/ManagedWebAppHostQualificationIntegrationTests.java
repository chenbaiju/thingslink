package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.dashboard.application.ApplicationManagementService;
import com.things.link.dashboard.application.DashboardManagementService;
import com.things.link.dashboard.application.publication.ApplicationPublicationService;
import com.things.link.dashboard.application.publication.DashboardPublicationService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0111：实际构建登记、生产Host/Data资格及普通APP事务构成同一个发布验收链。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "webapp.host-qualification.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class ManagedWebAppHostQualificationIntegrationTests {
    /** 专库保留所有发布历史，绝不借owner插版本模拟发布成功。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("managed_host_qualification").withUsername("thingslink").withPassword("thingslink");
    /** 完整生产上下文的真实Redis依赖。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 注册前解析真实绝对路径，避免macOS临时路径别名绕过工具的软链接拒绝。 */
    private static final Path REGISTRY = registerBuild();
    /** 当前候选内置PNG真实摘要，改资源必须同时重新取得资格。 */
    private static final String RESOURCE_DIGEST = "5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa";
    /** 普通APP连接，owner只用于前置身份和独立结果观察。 */
    @Autowired private JdbcTemplate application;
    /** 真实草稿管理，不绕过规范化及模型资格。 */
    @Autowired private DashboardManagementService dashboards;
    /** 真实看板双revision事务。 */
    @Autowired private DashboardPublicationService dashboardPublications;
    /** 真实应用草稿管理。 */
    @Autowired private ApplicationManagementService applications;
    /** 真实应用历史引用及发布资格。 */
    @Autowired private ApplicationPublicationService applicationPublications;

    static { DATABASE.start(); REDIS.start(); }

    /** 两版发布与回滚均真实成功；已成功的资格不能掩盖随后磁盘损坏或登记缺失。 */
    @Test
    void realRegisteredHostQualifiesPublicationAndRollbackButNeverCachesCorruptQualification() throws Exception {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("managed_host_qualification");
        UUID tenant = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID actor = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'真实宿主资格')", tenant);
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','资格发布者')", actor, actor + "@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), tenant, actor);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'资格项目','sh-1',?)", project, tenant, "hq_" + project.toString().replace("-", ""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", UUID.randomUUID(), project, actor);
        UUID model = UUID.randomUUID(); UUID type = UUID.randomUUID();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES (?,?,?,?,'资格模型','STANDARD','DIRECT')", type, tenant, project, "type_" + type.toString().replace("-", ""));
        String modelJson = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"minimum\":-50,\"maximum\":150}},\"events\":{},\"commands\":{}}";
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                """, model, tenant, project, type, modelJson, modelJson);
        String digest = owner.queryForObject("SELECT schema_digest FROM dev_thing_model_version WHERE id=?", String.class, model);
        as(tenant, project, actor, () -> {
            var dashboard = dashboards.create(project, "真实静态资源看板", schema("第一版", model, digest));
            var first = dashboardPublications.publish(project, dashboard.id(), "0", "0");
            dashboards.saveDraft(project, dashboard.id(), "0", schema("第二版", model, digest));
            var second = dashboardPublications.publish(project, dashboard.id(), "1", "1");
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(dashboardPublications.rollback(project, dashboard.id(), first.id(), "2").id()).isEqualTo(first.id());
            assertThat(owner.queryForObject("SELECT current_version_id FROM dash_dashboard WHERE id=?", UUID.class, dashboard.id())).isEqualTo(first.id());
            assertThat(owner.queryForObject("SELECT publication_revision FROM dash_dashboard WHERE id=?", Long.class, dashboard.id())).isEqualTo(3L);
            var app = applications.create(project, "真实宿主应用", applicationDraft("第一版", dashboard.id(), first.id()));
            var appFirst = applicationPublications.publish(project, app.id(), "0", "0");
            applications.saveDraft(project, app.id(), "0", applicationDraft("第二版", dashboard.id(), second.id()));
            var appSecond = applicationPublications.publish(project, app.id(), "1", "1");
            assertThat(appSecond.id()).isNotEqualTo(appFirst.id());
            assertThat(applicationPublications.rollback(project, app.id(), appFirst.id(), "2").id()).isEqualTo(appFirst.id());
            assertThat(owner.queryForObject("SELECT current_version_id FROM app_application WHERE id=?", UUID.class, app.id())).isEqualTo(appFirst.id());
            assertThat(owner.queryForObject("SELECT publication_revision FROM app_application WHERE id=?", Long.class, app.id())).isEqualTo(3L);
            assertThat(owner.queryForObject("SELECT count(*) FROM dash_dashboard_version WHERE dashboard_id=?", Long.class, dashboard.id())).isEqualTo(2L);
            assertThat(owner.queryForObject("SELECT count(*) FROM app_application_version WHERE application_id=?", Long.class, app.id())).isEqualTo(2L);

            // 保留可发布的新草稿，使失败由宿主重验触发，而不是无变化/过期revision提前短路。
            dashboards.saveDraft(project, dashboard.id(), "1", schema("待发布第三版", model, digest));
            applications.saveDraft(project, app.id(), "1", applicationDraft("待发布第三版", dashboard.id(), second.id()));
            Path host = REGISTRY.resolve("hosts/1.0.0");
            Path resource;
            try (var paths = Files.walk(host.resolve("release/assets"))) {
                resource = paths.filter(path -> path.getFileName().toString().equals(RESOURCE_DIGEST + ".png")).findFirst().orElseThrow();
            }
            for (Path corrupted : List.of(resource, host.resolve("webapp-host.zip"))) {
                byte[] original = Files.readAllBytes(corrupted);
                try {
                    byte[] changed = original.clone(); changed[changed.length - 1] ^= 1; Files.write(corrupted, changed);
                    assertRejectedWithoutWrites(owner, project, dashboard.id(), app.id(), second.id(), appSecond.id());
                } finally { Files.write(corrupted, original); }
            }
            Path hidden = REGISTRY.resolve("hosts/temporarily-unregistered");
            Files.move(host, hidden);
            try { assertRejectedWithoutWrites(owner, project, dashboard.id(), app.id(), second.id(), appSecond.id()); }
            finally { Files.move(hidden, host); }
            // 恢复同一批真实文件后可正常继续发布；拒绝路径未消耗版本或revision。
            dashboardPublications.publish(project, dashboard.id(), "2", "3");
            applicationPublications.publish(project, app.id(), "2", "3");
            return null;
        });
        assertThat(application.queryForObject("SELECT count(*) FROM dash_dashboard", Long.class)).isZero();
        assertThat(application.queryForObject("SELECT count(*) FROM app_application", Long.class)).isZero();
    }

    /** 发布和历史回滚都重新确权，版本/引用/指针/全项目审计必须逐字保持。 */
    private void assertRejectedWithoutWrites(JdbcTemplate owner, UUID project, UUID dashboard, UUID app,
            UUID dashboardVersion, UUID applicationVersion) {
        List<String> before = facts(owner, project);
        assertCode(() -> dashboardPublications.publish(project, dashboard, "2", "3"), 60041);
        assertCode(() -> applicationPublications.publish(project, app, "2", "3"), 60045);
        assertCode(() -> dashboardPublications.rollback(project, dashboard, dashboardVersion, "3"), 60041);
        assertCode(() -> applicationPublications.rollback(project, app, applicationVersion, "3"), 60045);
        assertThat(facts(owner, project)).isEqualTo(before);
    }

    /** 整行JSON记录覆盖版本、双revision、关联及审计，避免只计数漏掉指针变更。 */
    private static List<String> facts(JdbcTemplate owner, UUID project) {
        return List.of("dash_dashboard", "dash_dashboard_version", "dash_dashboard_version_model_ref",
                "app_application", "app_application_version", "app_application_version_dashboard_ref", "sys_audit_log")
                .stream().map(table -> table + ":" + owner.queryForObject(
                        "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                                + table + " t WHERE project_id=?", String.class, project)).toList();
    }

    /** 只接受精确公开业务码，基础设施异常不得冒充已验证的资格拒绝。 */
    private static void assertCode(Callable<?> call, int code) {
        assertThatThrownBy(call::call).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }

    /** 发布服务从可信Console身份建立普通APP RLS范围，不把owner连接交给业务。 */
    private static <T> T as(UUID tenant, UUID project, UUID actor, Callable<T> call) throws Exception {
        TenantContext.set(new TenantScope(tenant, project, actor));
        try { return call.call(); } finally { TenantContext.clear(); RlsScopeContext.clear(); }
    }

    /** 目录、当前值与真实PNG共同验证生产Data/Host/资源资格，避免空需求真空通过。 */
    private static byte[] schema(String title, UUID model, String digest) {
        return ("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},
                 "models":[{"key":"sensor_model","versionId":"%s","digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"%s","profile":"TC_PROPERTY_COMPOSITE_V1"}],
                 "variables":[{"key":"sensor","type":"DEVICE_SINGLE","title":"设备","modelKey":"sensor_model"}],"pages":[{"id":"overview","title":"%s","components":[
                  {"id":"caption","kind":"TEXT","componentVersion":"1.0.0","layout":{"x":0,"y":0,"w":12,"h":4},"props":{"content":"真实资格"},"bindings":{}},
                  {"id":"mark","kind":"IMAGE","componentVersion":"1.0.0","layout":{"x":12,"y":0,"w":12,"h":4},"props":{"resourceId":"device_mark","resourceDigest":"%s","alt":"设备"},"bindings":{}},
                  {"id":"selector","kind":"DEVICE_SELECTOR","componentVersion":"1.0.0","layout":{"x":0,"y":4,"w":12,"h":4},"props":{"pageSize":10},"bindings":{"directory":{"source":"DEVICE_DIRECTORY","variableKey":"sensor"}}},
                  {"id":"value","kind":"VALUE_CARD","componentVersion":"1.0.0","layout":{"x":12,"y":4,"w":12,"h":4},"props":{},"bindings":{"value":{"source":"CURRENT_VALUE","propertyKey":"temperature","device":{"variableKey":"sensor"}}}}
                 ]}]}
                """).formatted(model, digest, title, RESOURCE_DIGEST).getBytes(StandardCharsets.UTF_8);
    }

    /** 两版应用精确引用各自不可变看板版本，回滚不会借当前看板指针偷换历史。 */
    private static byte[] applicationDraft(String title, UUID dashboard, UUID version) {
        return ("""
                {"formatVersion":"tc.application/v1","displayName":"%s",
                 "hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"2.0.0"},
                 "dashboardRefs":[{"dashboardId":"%s","dashboardVersionId":"%s","title":"精确历史入口"}],"entryDashboardId":"%s"}
                """).formatted(title, dashboard, version, dashboard).getBytes(StandardCharsets.UTF_8);
    }

    /** 不执行构建，只通过正式CLI登记root预先验证的当前真实候选；失败保留独立日志首因。 */
    private static Path registerBuild() {
        try {
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("things-link-webapp"))) { root = root.getParent(); }
            if (root == null) { throw new IllegalStateException("找不到WebApp仓库根"); }
            Path registry = Files.createTempDirectory("webapp-host-qualification-").toRealPath();
            Path log = Files.createTempFile("webapp-host-registration-", ".log");
            Process process = new ProcessBuilder("python3", "scripts/host-candidate.py", "register",
                    "--registry-directory", registry.toString()).directory(root.resolve("things-link-webapp").toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("宿主登记超时，日志：" + log);
            }
            if (process.exitValue() != 0) { throw new IllegalStateException("宿主登记失败，日志：" + log + "\n" + Files.readString(log)); }
            return registry;
        } catch (Exception failure) { throw new IllegalStateException("当前真实宿主候选登记失败", failure); }
    }

    /** 实际生产Host适配读取本例登记；测试不替换Host/Data端口。 */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "thingslink_app"); registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl); registry.add("spring.flyway.user", DATABASE::getUsername); registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.dashboard.host.registry-directory", REGISTRY::toString);
        registry.add("things-link.outbox.publisher.enabled", () -> "false"); registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
