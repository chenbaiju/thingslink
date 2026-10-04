package com.things.link.bootstrap.rule;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 真HTTP、普通APP角色、真实Worker；管理员仅播种账号和项目身份，业务规则必须由API建立。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleManagementHttpTests extends AbstractIntegrationTest {
    /** 实际随机服务端口。 */
    @Value("${local.server.port}") private int port;
    /** 正常JWT签发，HTTP继续经过真实过滤器与成员复核。 */
    @Autowired private TokenIssuer tokens;
    /** 真实登录口令哈希。 */
    @Autowired private PasswordEncoder passwords;
    /** 应用运行连接，用于确认并未使用owner。 */
    @Autowired private JdbcTemplate runtime;
    /** 有界HTTP客户端，不读取系统代理。 */
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** JSON编解码。 */
    private final ObjectMapper mapper = new ObjectMapper();

    private final java.util.List<UUID> fixtureProjects = new java.util.ArrayList<>();

    /** 规则事实由创建它们的用例清理，不依赖其他类的全库DELETE顺序。 */
    @org.junit.jupiter.api.AfterEach
    void removeOwnedRuleFacts() throws java.sql.SQLException {
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            for (UUID project : fixtureProjects) {
                for (String sql : java.util.List.of(
                        "DELETE FROM rule_debug_event WHERE project_id=?",
                        "DELETE FROM rule_execution_log WHERE project_id=?",
                        "DELETE FROM rule_execution_receipt WHERE project_id=?",
                        "UPDATE rule_message SET status='DRAFT',active_version_id=NULL WHERE project_id=?",
                        "DELETE FROM rule_version WHERE project_id=?",
                        "DELETE FROM rule_message WHERE project_id=?")) {
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, project); statement.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
    }

    /** 完整规则生命周期、源码动作保留、无副作用调试、分页/CAS/归档/角色均经HTTP。 */
    @Test void managesImmutableRulesAndRejectsUnauthorizedAndStaleWrites() throws Exception {
        Fixture f = seed();
        assertThat(runtime.queryForObject("select current_user", String.class)).isEqualTo("thingslink_app");
        String base = "/api/v1/projects/" + f.project() + "/message-rules";
        var one = call(f, "POST", base, body("第一规则", "input => ({value: input.value + 1})", null), 201);
        String id = one.get("id").stringValue();
        var first = call(f, "GET", base + "/" + id + "/version-history", null, 200).get("items").get(0);
        assertThat(first.get("actions").size()).isEqualTo(1);
        var saved = call(f, "PUT", base + "/" + id, body("第一规则", "input => input", 1), 200);
        assertThat(saved.get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(saved.get("activeVersionId").isNull()).isTrue();
        call(f, "PUT", base + "/" + id, body("陈旧写入", "input => input", 1), 409);
        var published = call(f, "POST", base + "/" + id + "/versions/" + first.get("id").stringValue() + "/activate?expectedVersion=2", null, 200);
        assertThat(published.get("status").stringValue()).isEqualTo("ACTIVE");
        var debug = call(f, "POST", base + "/" + id + "/versions/" + first.get("id").stringValue() + "/debug", Map.of("inputJson", "{\"value\":4,\"token\":\"secret\"}"), 200);
        assertThat(debug.get("status").stringValue()).isEqualTo("SUCCESS");
        assertThat(mapper.readTree(debug.get("outputJson").stringValue()).get("value").intValue()).isEqualTo(5);
        assertThat(owner().queryForObject("select count(*) from rule_notification_delivery where project_id=?", Integer.class, f.project())).isZero();
        assertThat(owner().queryForObject("select input_summary from rule_debug_event where id=?", String.class, UUID.fromString(debug.get("eventId").stringValue()))).doesNotContain("secret");
        call(f, "POST", base + "/" + id + "/pause?expectedVersion=3", null, 200);
        call(f, "POST", base + "/" + id + "/pause?expectedVersion=4", null, 409);
        call(f, "POST", base + "/" + id + "/versions/" + first.get("id").stringValue() + "/activate?expectedVersion=4", null, 200);
        call(f, "POST", base, body("第二规则", "input => input", null), 201);
        var page = call(f, "GET", base + "?limit=1", null, 200);
        String cursor = page.get("nextCursor").stringValue();
        assertThat(call(f, "GET", base + "?limit=1&cursor=" + cursor, null, 200).get("items").size()).isEqualTo(1);
        call(f, "GET", base + "?limit=1&status=ACTIVE&cursor=" + cursor, null, 400);
        var bad = body("坏动作", "input => input", null); bad.put("actions", java.util.List.of(Map.of("nodeType", "payload-property-compare", "config", Map.of("pointer", "/a", "operator", "EXISTS"))));
        call(f, "POST", base, bad, 400);
        var other = seed();
        call(other, "GET", base + "/" + id, null, 404);
        for (String role : java.util.List.of("OPERATOR", "VIEWER")) {
            owner().update("update sys_project_member set role=? where project_id=? and account_id=?", role, f.project(), f.actor());
            call(f, "GET", base, null, 403); call(f, "POST", base, body("拒绝", "input => input", null), 403);
        }
        owner().update("update sys_project_member set role='ADMIN' where project_id=? and account_id=?", f.project(), f.actor());
        call(f, "GET", base + "/" + id, null, 200);
        owner().update("update sys_project set status='ARCHIVED' where id=?", f.project());
        call(f, "GET", base + "/" + id + "/version-history", null, 200);
        var denied = call(f, "POST", base + "/" + id + "/pause?expectedVersion=5", null, 403);
        assertThat(denied.get("code").intValue()).isEqualTo(50017);
        owner().update("update sys_project set status='ACTIVE' where id=?", f.project());
        call(f, "DELETE", base + "/" + id + "?expectedVersion=5", null, 204);
        call(f, "GET", base + "/" + id, null, 404);
        assertThat(owner().queryForObject("select count(*) from rule_version where rule_id=?", Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    /** 两个真实连接并发修订只有一个赢家；归档持锁先提交则等待中的写入不得落库。 */
    @Test void serializesConcurrentRevisionsAndWaitsForProjectArchive() throws Exception {
        Fixture f = seed(); String base = "/api/v1/projects/" + f.project() + "/message-rules";
        var created = call(f, "POST", base, body("并发规则", "input => input", null), 201);
        String path = base + "/" + created.get("id").stringValue();
        java.util.concurrent.Callable<Integer> writer = () -> send(f, "PUT", path, body("并发规则", "input => input", 1)).statusCode();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(writer); var second = executor.submit(writer);
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(owner().queryForObject("select count(*) from rule_version where rule_id=?", Integer.class,
                    UUID.fromString(created.get("id").stringValue()))).isEqualTo(2);
            try (var blocker = POSTGRES.createConnection("")) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var statement = blocker.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) { result.next(); blockerPid = result.getInt(1); }
                try (var lock = blocker.prepareStatement("select id from sys_project where id=? for update")) { lock.setObject(1, f.project()); lock.executeQuery().close(); }
                var pending = executor.submit(() -> send(f, "PUT", path, body("不应保存", "input => input", 2)));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                        "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", Integer.class, blockerPid) > 0);
                try (var update = blocker.prepareStatement("update sys_project set status='ARCHIVED' where id=?")) { update.setObject(1, f.project()); update.executeUpdate(); }
                blocker.commit();
                var denied = pending.get(10, TimeUnit.SECONDS);
                assertThat(denied.statusCode()).isEqualTo(403);
                assertThat(mapper.readTree(denied.body()).get("code").intValue()).isEqualTo(50017);
            }
        }
        assertThat(call(f, "GET", path, null, 200).get("name").stringValue()).isEqualTo("并发规则");
    }

    /** 实际Console生产构建与浏览器，后端和Worker不使用接口桩。 */
    @Test @EnabledIfSystemProperty(named="thingslink.test.rule-browser", matches="true")
    void realConsoleBrowserManagesRules() throws Exception {
        Fixture f = seed();
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path log = root.resolve(System.getProperty("thingslink.test.rule-evidence-dir",
                "logs/verify/g3-rule-1")).resolve("browser.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/console-rule-management-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("RULE_BACKEND", "http://127.0.0.1:" + port,
                "RULE_EMAIL", f.email(), "RULE_PASSWORD", "RuleJourney-Only-2030!", "RULE_PROJECT", f.project().toString()));
        Process process = builder.start();
        try { assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue(); System.out.println(Files.readString(log)); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** HTTP直连返回真实错误，成功是无信封JSON，204为空。 */
    private JsonNode call(Fixture f, String method, String path, Object body, int expected) throws Exception {
        var response = send(f, method, path, body);
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expected);
        return response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
    }
    /** 可并发的真实HTTP，只有明确的429允许一次有界重试。 */
    private HttpResponse<String> send(Fixture f, String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + tokens.issue(new AuthenticatedPrincipal(f.actor(), f.tenant(), f.project())).value())
                .header("Content-Type", "application/json");
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) { Thread.sleep(1100); response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
        return response;
    }
    /** 完整可编辑配置，调试不得派发该动作。 */
    private java.util.LinkedHashMap<String,Object> body(String name, String source, Integer version) {
        var body = new java.util.LinkedHashMap<String,Object>(); body.put("name", name); body.put("source", source);
        if (version != null) body.put("expectedVersion", version);
        body.put("actions", java.util.List.of(Map.of("nodeType", "notification-action", "config", Map.of("channel", "EMAIL", "recipient", "local@example.com")))); return body;
    }
    /** 用迁移owner创建身份夹具，不插入任何规则版本。 */
    private Fixture seed() {
        UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID(), project = UUID.randomUUID(); String email = actor + "@example.com";
        fixtureProjects.add(project);
        var db = owner(); db.update("insert into sys_tenant(id,name) values (?, '规则测试')", tenant);
        db.update("insert into sys_account(id,email,password_hash,display_name,email_verified_at) values (?,?,?,'规则管理员',now())", actor,email,passwords.encode("RuleJourney-Only-2030!"));
        db.update("insert into sys_tenant_member(id,tenant_id,account_id) values (?,?,?)", UUID.randomUUID(),tenant,actor);
        db.update("insert into sys_project(id,tenant_id,name,region,project_key) values (?,?,'规则测试项目','sh-1',?)", project,tenant,"p"+project.toString().replace("-",""));
        db.update("insert into sys_project_member(id,project_id,account_id,role) values (?,?,?,'OWNER')", UUID.randomUUID(),project,actor);
        return new Fixture(tenant,actor,project,email);
    }
    /** 仅用于夹具和跨范围最终事实核对。 */
    private JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
    /** @param tenant 归属租户 @param actor 管理员 @param project 项目 @param email 真实登录标识 */
    private record Fixture(UUID tenant, UUID actor, UUID project, String email) { }
}
