package com.things.link.bootstrap.project.quota;

import com.things.link.bootstrap.project.quota.fixture.DailyUsageRecoveryProcess;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.core.env.Environment;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4-4c：真实HTTP业务与REST不可变事实，经统计子进程强杀和原120秒自然租约恢复后，
 * 再从真实quota HTTP端点读取投影。不是完整服务重启、注解调度或自然UTC跨日证明。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "things-link.quota.daily-usage-recording-enabled=true")
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DailyUsageHttpProcessRecoveryTests {
    private static final String APP_ROLE = "thingslink_app";
    private static final String APP_ROLE_PASSWORD = "thingslink";
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("usage_process_recovery").withUsername("thingslink").withPassword("thingslink");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final String URL = startDatabase();
    private static String startDatabase() { DATABASE.start(); REDIS.start(); return DATABASE.getJdbcUrl(); }
    @Autowired TokenIssuer tokens;
    @Autowired JdbcTemplate appJdbc;
    @Autowired Environment environment;
    @Autowired Flyway flyway;
    @Autowired StringRedisTemplate redis;
    @Value("${local.server.port}") int port;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 计量保持开启，quota读取本身追加第五事实；展示仍是已恢复的第四投影快照。 */
    @Test @Timeout(240)
    void httpFactsSurviveClaimantCrashAndAppearInQuotaAfterNaturalLeaseRecovery() throws Exception {
        var owner = new JdbcTemplate(new DriverManagerDataSource(URL, DATABASE.getUsername(), DATABASE.getPassword()));
        UUID tenant = Uuid7.generate(), project = Uuid7.generate(), policy = Uuid7.generate(), account = Uuid7.generate();
        Path directory = Files.createDirectories(Path.of("..", "..", "logs", "verification", "r4-usage-http-process", UUID.randomUUID().toString()).toAbsolutePath());
        var children = new ArrayList<Process>();
        Throwable primary = null;
        // Prove all initialized clients use the owned schema before seeding or scheduling cleanup.
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(URL);
        assertThat(environment.getProperty("spring.flyway.url")).isEqualTo(URL);
        assertThat(appJdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE.getDatabaseName());
        assertThat(appJdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(appJdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        try (var migration = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(migration.getMetaData().getURL()).isEqualTo(URL);
            assertThat(migration.getCatalog()).isEqualTo(DATABASE.getDatabaseName());
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_project", Long.class)).isZero();
        try {
            owner.update("INSERT INTO sys_quota_policy(id,code,device_count_limit,rest_api_call_daily_limit) VALUES (?,?,10,10)", policy, "R4H" + policy.toString().replace("-", "").substring(0,20));
            owner.update("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES (?,'Owned HTTP usage recovery',?)", tenant, policy);
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unused','Owned HTTP usage')", account, account + "@example.com");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), tenant, account);
            owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key,usage_reconcile_after) VALUES (?,?,'Owned HTTP usage recovery','sh-1',?,now()-interval '1 minute')", project,tenant,"r4h"+project.toString().replace("-",""));
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", Uuid7.generate(), project, account);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String token = tokens.issue(new AuthenticatedPrincipal(account, tenant, project)).value();
            String base = "/api/v1/projects/" + project;
            assertThat(request("GET", base + "/devices", null, null).statusCode()).isEqualTo(401);
            assertThat(count(owner, "sys_usage_fact", project)).isZero();

            var create = request("POST", base + "/devices", "{\"deviceKey\":\"owned-http-usage\",\"name\":\"Owned HTTP usage\"}", token);
            assertThat(create.statusCode()).isEqualTo(201);
            UUID device = UUID.fromString(JSON.readTree(create.body()).path("id").asString());
            assertThat(owner.queryForObject("SELECT count(*) FROM dev_device WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL", Long.class, tenant, project, device)).isEqualTo(1);
            var list = request("GET", base + "/devices", null, token);
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(list.body()).size()).isEqualTo(1);
            assertThat(JSON.readTree(list.body()).get(0).path("id").asString()).isEqualTo(device.toString());
            var baseline = request("GET", base + "/quota", null, token);
            assertThat(baseline.statusCode()).isEqualTo(200);
            assertQuota(JSON.readTree(baseline.body()), project, 0);
            assertThat(count(owner, "sys_usage_fact", project)).isEqualTo(3);
            assertThat(count(owner, "sys_usage_counter_daily", project)).isZero();
            var originalFacts = restFacts(owner, tenant, project, today);
            assertThat(originalFacts).hasSize(3);

            var claimant = start(directory.resolve("claimant"), "claim-hold", tenant, project);
            children.add(claimant);
            awaitMarker(claimant, directory.resolve("claimant/claimed"), 20);
            Timestamp lease = owner.queryForObject("SELECT usage_reconcile_lease_until FROM sys_project WHERE id=?", Timestamp.class, project);
            assertThat(lease).isNotNull();
            assertThat(owner.queryForObject("SELECT extract(epoch from usage_reconcile_lease_until-clock_timestamp()) FROM sys_project WHERE id=?", Double.class, project)).isBetween(110d,120d);
            claimant.destroyForcibly();
            assertThat(claimant.waitFor(10, TimeUnit.SECONDS)).as("owned claimant reaped").isTrue();
            var duringFailure = request("GET", base + "/devices/" + device, null, token);
            assertThat(duringFailure.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(duringFailure.body()).path("id").asString()).isEqualTo(device.toString());
            var recoveryFacts = restFacts(owner, tenant, project, today);
            assertThat(recoveryFacts).hasSize(4).containsAll(originalFacts);
            assertThat(count(owner, "sys_usage_counter_daily", project)).isZero();

            var replacement = start(directory.resolve("replacement"), "scan", tenant, project);
            children.add(replacement);
            assertThat(replacement.pid()).isNotEqualTo(claimant.pid());
            awaitMarker(replacement, directory.resolve("replacement/ready"), 20);
            assertThat(owner.queryForObject("SELECT usage_reconcile_lease_until FROM sys_project WHERE id=?", Timestamp.class, project)).isEqualTo(lease);
            assertThat(Files.exists(directory.resolve("replacement/claimed"))).isFalse();
            awaitMarker(replacement, directory.resolve("replacement/completed"), 150);
            assertThat(replacement.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(replacement.exitValue()).isZero();
            assertThat(owner.queryForObject("SELECT updated_at>=? AND usage_reconcile_lease_until IS NULL AND usage_reconcile_after>updated_at FROM sys_project WHERE id=?", Boolean.class, lease, project)).isTrue();
            assertThat(projected(owner, project, today)).isEqualTo(4);
            assertThat(restFacts(owner, tenant, project, today)).containsExactlyElementsOf(recoveryFacts);

            var display = request("GET", base + "/quota", null, token);
            assertThat(display.statusCode()).isEqualTo(200);
            assertQuota(JSON.readTree(display.body()), project, 4);
            // The real quota GET is itself metered. It does not synchronously advance the projection.
            assertThat(restFacts(owner, tenant, project, today)).hasSize(5).containsAll(recoveryFacts);
            assertThat(projected(owner, project, today)).isEqualTo(4);
            assertThat(owner.queryForObject("SELECT count(*) FROM dev_device WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL", Long.class, tenant, project, device)).isEqualTo(1);
            assertThat(LocalDate.now(ZoneOffset.UTC)).as("ordinary-day run; not natural UTC rollover").isEqualTo(today);
            Files.writeString(directory.resolve("result.json"), "{\"result\":\"PASS\",\"processes\":2,\"productionLeaseSeconds\":120,\"http\":true,\"recoveredFacts\":4,\"displayedProjection\":4,\"factsAfterQuotaRead\":5,\"fullServerRestart\":false,\"naturalUtcRollover\":false}\n");
        } catch (Exception | AssertionError failure) {
            primary = failure;
            throw failure;
        } finally {
            List<Exception> failures = new ArrayList<>();
            for (var child : children) try {
                if (child.isAlive()) child.destroyForcibly();
                if (!child.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owned usage child was not reaped");
            } catch (Exception failure) { failures.add(failure); }
            for (String table : List.of("dev_device", "sys_usage_fact", "sys_usage_counter_daily", "sys_project_member"))
                try { owner.update("DELETE FROM " + table + " WHERE project_id=?", project); } catch (Exception failure) { failures.add(failure); }
            try { owner.update("DELETE FROM sys_project WHERE id=?", project); } catch (Exception failure) { failures.add(failure); }
            try { owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", tenant); } catch (Exception failure) { failures.add(failure); }
            try { owner.update("DELETE FROM sys_account WHERE id=?", account); } catch (Exception failure) { failures.add(failure); }
            try { owner.update("DELETE FROM sys_tenant WHERE id=?", tenant); } catch (Exception failure) { failures.add(failure); }
            try { owner.update("DELETE FROM sys_quota_policy WHERE id=?", policy); } catch (Exception failure) { failures.add(failure); }
            for (UUID id : List.of(account, project, tenant)) for (String kind : List.of("read", "write")) for (String scope : List.of("account", "project", "tenant"))
                try { redis.delete("quota:rest:rate:" + kind + ":" + scope + ":" + id); } catch (Exception failure) { failures.add(failure); }
            if (!failures.isEmpty()) {
                var cleanup = new IllegalStateException("Owned HTTP usage recovery cleanup incomplete");
                failures.forEach(cleanup::addSuppressed);
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    /** 只使用真实签名Bearer与回环HTTP，响应正文不写日志。 */
    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 比较不可变事实全部业务字段，不能用相同总数掩盖替换或改写。 */
    private List<java.util.Map<String, Object>> restFacts(JdbcTemplate owner, UUID tenant, UUID project, LocalDate today) {
        return owner.queryForList("SELECT id,tenant_id,project_id,metric,event_key,usage_date,occurred_at,created_at FROM sys_usage_fact WHERE tenant_id=? AND project_id=? AND metric='REST_API_CALL' AND usage_date=? ORDER BY id", tenant, project, today);
    }

    private long projected(JdbcTemplate owner, UUID project, LocalDate today) {
        return owner.queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='REST_API_CALL' AND usage_date=?", Long.class, project, today);
    }

    /** HTTP展示以已恢复投影为准，同租户唯一项目贡献与共享池相等。 */
    private void assertQuota(JsonNode body, UUID project, long used) {
        assertThat(body.path("projectId").asString()).isEqualTo(project.toString());
        for (String scope : List.of("project", "tenantSharedPool")) {
            var metrics = body.path(scope).path("dailyMetrics");
            List<JsonNode> matching = new ArrayList<>();
            metrics.forEach(metric -> { if (metric.path("metric").asString().equals("REST_API_CALL")) matching.add(metric); });
            assertThat(matching).hasSize(1);
            JsonNode metric = matching.getFirst();
            assertThat(metric.path("limit").asLong()).isEqualTo(10);
            assertThat(metric.path("used").asLong()).isEqualTo(used);
            assertThat(metric.path("remaining").asLong()).isEqualTo(10 - used);
            assertThat(metric.path("status").asString()).isEqualTo("NORMAL");
        }
    }
    private long count(JdbcTemplate jdbc,String table,UUID project){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Long.class,project);}
    private Process start(Path directory,String mode,UUID tenant,UUID project)throws Exception{
        Files.createDirectories(directory);
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx192m","-cp",classpath,DailyUsageRecoveryProcess.class.getName());
        var settings = new java.util.Properties();
        settings.putAll(java.util.Map.of("R4_USAGE_DIRECTORY",directory.toString(),"R4_USAGE_MODE",mode,"R4_USAGE_TENANT",tenant.toString(),"R4_USAGE_PROJECT",project.toString(),"R4_USAGE_URL",URL,"R4_USAGE_USER",APP_ROLE,"R4_USAGE_PASSWORD",APP_ROLE_PASSWORD));
        Path log=directory.resolve("process.log");
        Files.createFile(log);
        if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(log,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        Process child=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try(var input=child.getOutputStream()) { settings.store(input,null); }
        catch(Exception failure){child.destroyForcibly();if(!child.waitFor(10,TimeUnit.SECONDS))failure.addSuppressed(new IllegalStateException("Owned child not reaped after private configuration failure"));throw failure;}
        return child;
    }
    private void awaitMarker(Process child,Path marker,int seconds){
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofMillis(100)).until(()->{
            if(Files.exists(marker))return true;
            assertThat(child.isAlive()).as("Owned child remains alive until marker; inspect private process.log").isTrue();return false;
        });
    }
    /** Before web-server/security eager bean creation, bind both Flyway and APP to this owned DB. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.flyway.url", () -> URL);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.quota.daily-reconciliation.enabled", () -> "false");
        registry.add("things-link.quota.daily-usage-recording-enabled", () -> "true");
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
