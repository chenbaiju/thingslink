package com.things.link.bootstrap.rule;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.things.link.rule.domain.RuleSceneRepository;
import com.things.link.rule.application.RuleSceneExecutionProcessor;
import com.things.link.rule.application.outbox.RuleActionDispatcher;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

/** 真HTTP、普通APP角色、真实场景事务；管理员仅播种身份和设备夹具，业务场景必须由API建立。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SceneManagementHttpTests extends AbstractIntegrationTest {
    private final java.util.List<UUID> fixtureProjects = new java.util.ArrayList<>();

    /** 场景表的项目外键不级联；本类结束每个用例时只清理自己创建的场景事实。 */
    @org.junit.jupiter.api.AfterEach
    void removeOwnedSceneFacts() throws java.sql.SQLException {
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            for (UUID project : fixtureProjects) {
                for (String sql : java.util.List.of(
                        "DELETE FROM rule_notification_delivery WHERE project_id=?",
                        "DELETE FROM rule_scene_execution WHERE project_id=?",
                        "UPDATE rule_scene SET status='DRAFT',active_version_id=NULL WHERE project_id=?",
                        "DELETE FROM rule_scene_version WHERE project_id=?",
                        "DELETE FROM rule_scene WHERE project_id=?")) {
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, project);
                        statement.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
    }

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

    /** 只围绕真实持锁操作设置屏障，不替代业务返回值。 */
    @MockitoSpyBean private RuleSceneRepository sceneRepository;
    /** 执行进入处理器时原事务已经持场景锁。 */
    @MockitoSpyBean private RuleSceneExecutionProcessor processor;

    /** 故障点在真实派发完成后，验证整个原事务回滚。 */
    @MockitoSpyBean private RuleActionDispatcher dispatcher;

    /** 已写意图后抛错，不得遗留执行、部分Outbox或执行审计。 */
    @Test void dispatchFailureRollsBackExecutionOutboxAndAudit() throws Exception {
        Fixture f = seed(); String path = active(f);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT); })
                .when(dispatcher).dispatch(any(), any());
        execute(f, path, "rollback", Map.of("deviceId", f.device(), "payload", Map.of("temperature", 42)), 409);
        assertThat(owner().queryForObject("select count(*) from rule_scene_execution where project_id=?", Integer.class, f.project())).isZero();
        assertThat(owner().queryForObject("select count(*) from sys_outbox_event where project_id=?", Integer.class, f.project())).isZero();
        assertThat(owner().queryForObject("select count(*) from sys_audit_log where project_id=? and action='rule_scene.executed'", Integer.class, f.project())).isZero();
    }

    /** 真实HTTP覆盖完整版本/暂停/旧键回读/新键拒绝/条件终态及角色隔离。 */
    @Test void managesScenesAndPreservesFirstExecutionAcrossPauseAndVersionChange() throws Exception {
        Fixture f = seed(); String base = "/api/v1/projects/" + f.project() + "/scenes";
        assertThat(runtime.queryForObject("select current_user", String.class)).isEqualTo("thingslink_app");
        var created = call(f, "POST", base, body("手动场景", null), 201);
        String path = base + "/" + created.get("id").stringValue();
        var v1 = call(f, "GET", path + "/version-history", null, 200).get("items").get(0);
        call(f, "POST", path + "/versions/" + v1.get("id").stringValue() + "/activate?expectedVersion=1", null, 200);
        var input = Map.of("deviceId", f.device(), "payload", Map.of("temperature", 42));
        var first = execute(f, path, "same-key", input, 200);
        assertThat(first.get("status").stringValue()).isEqualTo("DISPATCHED");
        call(f, "POST", path + "/pause?expectedVersion=2", null, 200);
        assertThat(execute(f, path, "same-key", input, 200)).isEqualTo(first);
        assertThat(execute(f, path, "new-key", input, 409).get("code").intValue()).isEqualTo(40045);
        assertThat(execute(f, path, "same-key", Map.of("deviceId", f.device(), "payload", Map.of()), 409).get("code").intValue()).isEqualTo(40042);
        var changed = body("手动场景", 3); changed.put("conditions", java.util.List.of(Map.of("nodeType", "payload-property-compare", "config", Map.of("pointer", "/temperature", "operator", "GT", "value", 100))));
        call(f, "PUT", path, changed, 200);
        var v2 = call(f, "GET", path + "/version-history?limit=1", null, 200);
        assertThat(v2.get("nextCursor").isNull()).isFalse();
        call(f, "POST", path + "/versions/" + v2.get("items").get(0).get("id").stringValue() + "/activate?expectedVersion=4", null, 200);
        assertThat(execute(f, path, "same-key", input, 200)).isEqualTo(first);
        assertThat(execute(f, path, "false-key", input, 200).get("status").stringValue()).isEqualTo("SKIPPED");
        assertThat(owner().queryForObject("select count(*) from sys_outbox_event where project_id=? and payload::jsonb->>'sceneExecutionId' is not null", Integer.class, f.project())).isEqualTo(1);
        assertThat(call(f, "GET", path + "/versions/" + v1.get("id").stringValue(), null, 200).get("actions").size()).isEqualTo(1);
        var bad = body("错误用途", null); bad.put("conditions", java.util.List.of(Map.of("nodeType", "notification-action", "config", Map.of("channel", "EMAIL", "recipient", "local@example.com"))));
        assertThat(call(f, "POST", base, bad, 400).get("code").intValue()).isEqualTo(40044);
        bad.put("conditions", mapper.readTree("[{\"nodeType\":\"payload-property-compare\",\"config\":null}]"));
        assertThat(call(f, "POST", base, bad, 400).get("code").intValue()).isEqualTo(40044);
        bad.put("conditions", java.util.Arrays.asList((Object) null));
        assertThat(call(f, "POST", base, bad, 400).get("code").intValue()).isEqualTo(40044);
        for (String role : java.util.List.of("OPERATOR", "VIEWER")) {
            owner().update("update sys_project_member set role=? where project_id=? and account_id=?", role, f.project(), f.actor());
            call(f, "GET", base, null, 403); execute(f, path, "denied", input, 403);
        }
        owner().update("update sys_project_member set role='ADMIN' where project_id=? and account_id=?", f.project(), f.actor());
        var other = seed(); call(other, "GET", path, null, 404);
        owner().update("update sys_project set status='ARCHIVED' where id=?", f.project());
        call(f, "GET", path + "/version-history", null, 200);
        assertThat(call(f, "POST", path + "/pause?expectedVersion=5", null, 403).get("code").intValue()).isEqualTo(50017);
        owner().update("update sys_project set status='ACTIVE' where id=?", f.project());
        call(f, "POST", path + "/versions/" + v1.get("id").stringValue() + "/activate?expectedVersion=5", null, 200);
        call(f, "DELETE", path + "?expectedVersion=6", null, 204);
        assertThat(owner().queryForObject("select count(*) from rule_scene_version where scene_id=?", Integer.class, UUID.fromString(created.get("id").stringValue()))).isEqualTo(2);
    }

    /** 全部七类动作通过真实Schema校验后版本回读保留原始JSON类型与顺序，不实际外发。 */
    @Test void sevenActionConfigurationsRoundTripWithoutSideEffects() throws Exception {
        Fixture f = seed(); String base = "/api/v1/projects/" + f.project() + "/scenes";
        var actions = mapper.readTree("""
                [{"nodeType":"notification-action","config":{"channel":"EMAIL","recipient":"local@example.com"}},
                 {"nodeType":"email-action","config":{"recipient":"local@example.com","subject":"提醒","body":"正文"}},
                 {"nodeType":"webhook-action","config":{"url":"https://example.com/hook","body":"{}"}},
                 {"nodeType":"device-command-action","config":{"commandKey":"restart","input":{"wait":3,"enabled":true,"values":[1,"2",null]}}},
                 {"nodeType":"device-property-set-action","config":{"properties":{"on":true,"value":12.5}}},
                 {"nodeType":"alarm-create-action","config":{"alarmRuleId":"00000000-0000-0000-0000-000000000001"}},
                 {"nodeType":"alarm-clear-action","config":{"alarmRuleId":"00000000-0000-0000-0000-000000000001"}}]
                """);
        var request = body("七类动作", null); request.put("actions", actions);
        String path = base + "/" + call(f, "POST", base, request, 201).get("id").stringValue();
        assertThat(call(f, "GET", path + "/version-history", null, 200).get("items").get(0).get("actions")).isEqualTo(actions);
        assertThat(owner().queryForObject("select count(*) from sys_outbox_event where project_id=?", Integer.class, f.project())).isZero();
    }

    /** 同键并发由行锁串行化，受理事实和通知Outbox只产生一次。 */
    @Test void concurrentSameKeyReturnsOneImmutableFact() throws Exception {
        Fixture f = seed(); String path = active(f);
        var input = Map.of("deviceId", f.device(), "payload", Map.of("temperature", 42));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> execute(f, path, "parallel", input, 200));
            var b = pool.submit(() -> execute(f, path, "parallel", input, 200));
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(owner().queryForObject("select count(*) from rule_scene_execution where project_id=?", Integer.class, f.project())).isEqualTo(1);
        assertThat(owner().queryForObject("select count(*) from sys_outbox_event where project_id=? and payload::jsonb->>'sceneExecutionId' is not null", Integer.class, f.project())).isEqualTo(1);
    }

    /** 实际PG锁等待验证执行与暂停/发布的两个先后顺序。 */
    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void serializesManagementAndExecute(boolean executeFirst, boolean publishing) throws Exception {
        Fixture f = seed(); String path = active(f);
        String firstVersion = call(f, "GET", path, null, 200).get("activeVersionId").stringValue();
        String nextVersion = firstVersion;
        if (publishing) {
            call(f, "PUT", path, body("并发场景", 2), 200);
            nextVersion = call(f, "GET", path + "/version-history", null, 200).get("items").get(0).get("id").stringValue();
        }
        final String mutation = publishing ? path + "/versions/" + nextVersion + "/activate?expectedVersion=3" : path + "/pause?expectedVersion=2";
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<Object> barrier = invocation -> {
            Object result = invocation.callRealMethod();
            pid.set(runtime.queryForObject("select pg_backend_pid()", Integer.class)); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("锁屏障未释放");
            return result;
        };
        if (executeFirst) doAnswer(barrier).when(processor).process(any(), any(), any(), any(), any(), any());
        else {
            RuleSceneRepository target = AopTestUtils.getUltimateTargetObject(sceneRepository);
            if (publishing) doAnswer(barrier).when(target).activate(any(), any(), any(), anyLong());
            else doAnswer(barrier).when(target).pause(any(), any(), anyLong());
        }
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<JsonNode> execution = () -> execute(f, path, "ordered", Map.of("deviceId", f.device(), "payload", Map.of("temperature", 42)), executeFirst || publishing ? 200 : 409);
            java.util.concurrent.Callable<JsonNode> pause = () -> call(f, "POST", mutation, null, 200);
            var first = pool.submit(executeFirst ? execution : pause);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(executeFirst ? pause : execution);
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> owner().queryForObject("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", Integer.class, pid.get()) > 0);
            } finally { release.countDown(); }
            first.get(15, TimeUnit.SECONDS); var result = second.get(15, TimeUnit.SECONDS);
            if (!executeFirst && !publishing) assertThat(result.get("code").intValue()).isEqualTo(40045);
        } finally { release.countDown(); }
        assertThat(owner().queryForObject("select count(*) from rule_scene_execution where project_id=?", Integer.class, f.project())).isEqualTo(executeFirst || publishing ? 1 : 0);
        if (publishing) assertThat(owner().queryForObject("select scene_version_id::text from rule_scene_execution where project_id=?", String.class, f.project()))
                .isEqualTo(executeFirst ? firstVersion : nextVersion);
    }

    /** 在真实PG临时旧表上运行原追加迁移，旧行逐字段不变且暂停必须保留活动版本。 */
    @Test void upgradePreservesLegacyRowsAndRequiresPausedVersion() throws Exception {
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("create temporary table rule_scene(id int, status text, active_version_id uuid, constraint rule_scene_status_ck check(status in ('DRAFT','ACTIVE')), constraint rule_scene_status_active_ck check((status='DRAFT' and active_version_id is null) or (status='ACTIVE' and active_version_id is not null)))");
                statement.execute("insert into rule_scene values (1,'DRAFT',null),(2,'ACTIVE','00000000-0000-0000-0000-000000000001')");
                String before;
                try (var rows = statement.executeQuery("select jsonb_agg(to_jsonb(r) order by id)::text from rule_scene r")) { rows.next(); before = rows.getString(1); }
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("db/migration/rule/V20260920_0130__rule_scene_pause.sql"));
                try (var rows = statement.executeQuery("select jsonb_agg(to_jsonb(r) order by id)::text from rule_scene r")) { rows.next(); assertThat(rows.getString(1)).isEqualTo(before); }
                statement.execute("update rule_scene set status='PAUSED' where id=2");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute("update rule_scene set status='PAUSED' where id=1")).isInstanceOf(java.sql.SQLException.class);
            } finally { connection.rollback(); }
        }
    }

    /** 实际Console生产构建与浏览器，后端和Worker不使用接口桩。 */
    @Test @EnabledIfSystemProperty(named="thingslink.test.rule-browser", matches="true")
    void realConsoleBrowserManagesScenes() throws Exception {
        Fixture f = seed();
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Path log = root.resolve(System.getProperty("thingslink.test.scene-evidence-dir",
                "logs/verify/g3-rule-1b")).resolve("browser.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/console-scene-management-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("RULE_BACKEND", "http://127.0.0.1:" + port,
                "RULE_EMAIL", f.email(), "RULE_PASSWORD", "RuleJourney-Only-2030!", "RULE_PROJECT", f.project().toString()));
        Process process = builder.start();
        try { assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue(); System.out.println(Files.readString(log)); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** 建立真实活动场景，所有业务定义由HTTP写入。 */
    private String active(Fixture f) throws Exception {
        String base = "/api/v1/projects/" + f.project() + "/scenes";
        String path = base + "/" + call(f, "POST", base, body("并发场景", null), 201).get("id").stringValue();
        String version = call(f, "GET", path + "/version-history", null, 200).get("items").get(0).get("id").stringValue();
        call(f, "POST", path + "/versions/" + version + "/activate?expectedVersion=1", null, 200);
        return path;
    }
    /** 同键请求经真实HTTP头，网络重试不隐式换键。 */
    private JsonNode execute(Fixture f, String path, String key, Object body, int expected) throws Exception {
        var response = send(f, "POST", path + "/executions", body, key);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return mapper.readTree(response.body());
    }
    /** 真HTTP断言业务响应。 */
    private JsonNode call(Fixture f, String method, String path, Object body, int expected) throws Exception {
        var response = send(f, method, path, body, null);
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expected);
        return response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
    }
    /** 可并发的真实HTTP，只有明确的429允许一次有界重试。 */
    private HttpResponse<String> send(Fixture f, String method, String path, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + tokens.issue(new AuthenticatedPrincipal(f.actor(), f.tenant(), f.project())).value())
                .header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) { Thread.sleep(1100); response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
        return response;
    }
    /** 真实条件和通知动作，使用本地测试收件标识。 */
    private java.util.LinkedHashMap<String,Object> body(String name, Integer version) {
        var body = new java.util.LinkedHashMap<String,Object>(); body.put("name", name);
        if (version != null) body.put("expectedVersion", version);
        body.put("conditions", java.util.List.of(Map.of("nodeType", "payload-property-compare", "config", Map.of("pointer", "/temperature", "operator", "GT", "value", 30))));
        body.put("actions", java.util.List.of(Map.of("nodeType", "notification-action", "config", Map.of("channel", "EMAIL", "recipient", "local@example.com")))); return body;
    }
    /** 用迁移owner创建身份夹具，不插入任何规则版本。 */
    private Fixture seed() {
        UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID(), project = UUID.randomUUID(); String email = actor + "@example.com";
        var db = owner(); db.update("insert into sys_tenant(id,name) values (?, '规则测试')", tenant);
        db.update("insert into sys_account(id,email,password_hash,display_name,email_verified_at) values (?,?,?,'规则管理员',now())", actor,email,passwords.encode("RuleJourney-Only-2030!"));
        db.update("insert into sys_tenant_member(id,tenant_id,account_id) values (?,?,?)", UUID.randomUUID(),tenant,actor);
        db.update("insert into sys_project(id,tenant_id,name,region,project_key) values (?,?,'规则测试项目','sh-1',?)", project,tenant,"p"+project.toString().replace("-",""));
        fixtureProjects.add(project);
        db.update("insert into sys_project_member(id,project_id,account_id,role) values (?,?,?,'OWNER')", UUID.randomUUID(),project,actor);
        UUID type = UUID.randomUUID(), device = UUID.randomUUID();
        db.update("insert into dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) values (?,?,?,'scene_type','场景类型','STANDARD','DIRECT','PUBLISHED')",type,tenant,project);
        db.update("insert into dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) values (?,?,?,?,'scene_device','场景设备','ONLINE')",device,tenant,project,type);
        return new Fixture(tenant,actor,project,email,device);
    }
    /** 仅用于夹具和跨范围最终事实核对。 */
    private JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
    /** @param tenant 归属租户 @param actor 管理员 @param project 项目 @param email 真实登录标识 @param device 目标设备 */
    private record Fixture(UUID tenant, UUID actor, UUID project, String email, UUID device) { }
}
