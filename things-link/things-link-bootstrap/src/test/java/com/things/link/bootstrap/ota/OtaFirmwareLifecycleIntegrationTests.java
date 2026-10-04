package com.things.link.bootstrap.ota;

import com.things.link.device.infrastructure.persistence.JdbcOtaModelSnapshotAdapter;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 固件草稿真实HTTP、事务、模型投影与RLS联合验收，使用生产安全链及普通数据库身份。 */
@AutoConfigureMockMvc
class OtaFirmwareLifecycleIntegrationTests extends AbstractIntegrationTest {
    /** 仅解析响应，不用于绕过服务端原文字段验证。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例只清理自己创建的项目，避免共享数据库残留新外键。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 完整Console认证与幂等过滤链。 */
    @Autowired
    private MockMvc mvc;
    /** 生成生产可验证JWT，角色仍由真实数据库读取。 */
    @Autowired
    private TokenIssuer tokens;

    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id IN (?, ?)", fixture.accountId(), fixture.ownerId());
        }
        fixtures.clear();
    }

    /** 首次与持久重放身份/时间一致，完整读取/分页/取消及终态恢复不产生第二固件。 */
    @Test
    void createsReadsReplaysAndCancelsDraft() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String key = UUID.randomUUID().toString();
        JsonNode first = ok(write(fixture, base(fixture), key, body(fixture, "固件1.0.0")));
        JsonNode replay = ok(write(fixture, base(fixture), key, body(fixture, "固件1.0.0")));
        assertThat(replay).isEqualTo(first);
        assertThat(first.path("status").asText()).isEqualTo("DRAFT");
        assertThat(first.path("revision").isString()).isTrue();
        assertThat(first.path("revision").asText()).isEqualTo("0");
        assertThat(first.has("tenantId")).isFalse();
        assertThat(first.has("createdBy")).isFalse();
        assertThat(first.has("downloadUrl")).isFalse();
        String path = base(fixture) + "/" + first.path("id").asText();
        assertThat(ok(read(fixture, path))).isEqualTo(first);
        JsonNode page = ok(read(fixture, base(fixture) + "?limit=1"));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0)).isEqualTo(first);
        String cancelKey = UUID.randomUUID().toString();
        JsonNode cancelled = ok(write(fixture, path + "/cancel", cancelKey, "{\"expectedRevision\":\"0\"}"));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("revision").asText()).isEqualTo("1");
        assertThat(cancelled.path("cancelledAt").isNull()).isFalse();
        assertThat(ok(write(fixture, path + "/cancel", UUID.randomUUID().toString(),
                "{\"expectedRevision\":\"0\"}"))).isEqualTo(cancelled);
        error(write(fixture, path + "/cancel", cancelKey, "{\"expectedRevision\":\"0\"}"), 409, 10014);
        error(write(fixture, base(fixture), key, body(fixture, "固件1.0.0")), 409, 10014);
        error(write(fixture, path + "/cancel", UUID.randomUUID().toString(),
                "{\"expectedRevision\":\"9\"}"), 409, 70004);
    }

    /** 写角色限制与成员读取共用真实项目关系，未认证请求在Controller前拒绝。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void enforcesProjectRoleAndAuthentication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        MvcResult result = write(fixture, base(fixture), UUID.randomUUID().toString(), body(fixture, "1"));
        if (role == ProjectRole.OWNER || role == ProjectRole.ADMIN) {
            ok(result);
        } else {
            error(result, 403, 70002);
        }
        ok(read(fixture, base(fixture)));
        assertThat(mvc.perform(get(base(fixture))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** 同键改正文与创建后失权均不能恢复旧响应，也不能插入新记录。 */
    @Test
    void rejectsChangedRequestAndRevalidatesRoleOnReplay() throws Exception {
        Fixture fixture = seed(ProjectRole.ADMIN);
        String key = UUID.randomUUID().toString();
        ok(write(fixture, base(fixture), key, body(fixture, "1")));
        error(write(fixture, base(fixture), key, body(fixture, "2")), 409, 10009);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), fixture.accountId());
        error(write(fixture, base(fixture), key, body(fixture, "1")), 403, 70002);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware WHERE project_id=?", Long.class,
                fixture.projectId())).isEqualTo(1);
    }

    /** 隔离项目的ID不可通过可见项目路径枚举，类型/版本关系不能只凭各自存在认定。 */
    @Test
    void rejectsCrossProjectAndWrongModelBinding() throws Exception {
        Fixture first = seed(ProjectRole.OWNER);
        Fixture second = seed(ProjectRole.OWNER);
        JsonNode created = ok(write(second, base(second), UUID.randomUUID().toString(), body(second, "1")));
        error(read(first, base(first) + "/" + created.path("id").asText()), 404, 70001);
        String wrong = body(first, "1").replace(first.modelId().toString(), second.modelId().toString());
        error(write(first, base(first), UUID.randomUUID().toString(), wrong), 409, 70003);
        String wrongType = body(first, "1").replace(first.typeId().toString(), second.typeId().toString());
        error(write(first, base(first), UUID.randomUUID().toString(), wrongType), 409, 70003);
        owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?", first.typeId());
        error(write(first, base(first), UUID.randomUUID().toString(), body(first, "1")), 409, 70003);
    }

    /** 原文信封的未知、重复、null与类型错误不可被普通DTO静默抹去。 */
    @Test
    void rejectsMalformedAndOutOfContractBodies() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String valid = body(fixture, "1");
        for (String input : List.of("{}", "null", "[]", valid.replace("\"firmwareVersion\":\"1\"", "\"firmwareVersion\":null"),
                valid.replace("\"firmwareVersion\":\"1\"", "\"firmwareVersion\":1"),
                valid.replace("}", ",\"unexpected\":true}"),
                valid.replace("}", ",\"firmwareVersion\":\"2\"}"))) {
            error(write(fixture, base(fixture), UUID.randomUUID().toString(), input), 400, 10002);
        }
        for (String version : List.of(" ", "a".repeat(129), "\\n")) {
            error(write(fixture, base(fixture), UUID.randomUUID().toString(), body(fixture, version)), 400, 10001);
        }
        assertThat(write(fixture, base(fixture), null, valid).getResponse().getStatus()).isEqualTo(400);
        assertThat(read(fixture, base(fixture) + "?limit=101").getResponse().getStatus()).isEqualTo(400);
    }

    /** 真正并发的同键创建必须落到同一个稳定ID和微秒时间，不靠客户端串行掩盖。 */
    @Test
    void concurrentCreationHasOneDurableIdentity() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> ok(write(fixture, base(fixture), key, body(fixture, "1")))));
            }
            JsonNode first = futures.get(0).get(15, TimeUnit.SECONDS);
            for (var future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(first);
            }
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware WHERE project_id=?", Long.class,
                fixture.projectId())).isEqualTo(1);
    }

    /** 普通RLS连接不能看见其他项目或修改冻结字段，投影同时限定精确项目/type/model。 */
    @Test
    void databaseAndProjectionKeepIsolationAndImmutability() throws Exception {
        Fixture first = seed(ProjectRole.OWNER);
        Fixture second = seed(ProjectRole.OWNER);
        UUID id = UUID.fromString(ok(write(first, base(first), UUID.randomUUID().toString(), body(first, "1")))
                .path("id").asText());
        assertThat((Boolean) app(first, jdbc -> new JdbcOtaModelSnapshotAdapter(jdbc)
                .find(first.projectId(), first.typeId(), first.modelId()).isPresent())).isTrue();
        assertThat((Boolean) app(first, jdbc -> new JdbcOtaModelSnapshotAdapter(jdbc)
                .find(second.projectId(), second.typeId(), second.modelId()).isEmpty())).isTrue();
        assertThat((Boolean) app(first, jdbc -> new JdbcOtaModelSnapshotAdapter(jdbc)
                .find(first.projectId(), second.typeId(), first.modelId()).isEmpty())).isTrue();
        assertThat((Long) app(second, jdbc -> jdbc.queryForObject("SELECT count(*) FROM ota_firmware WHERE id=?", Long.class, id)))
                .isZero();
        assertThatThrownBy(() -> app(first, jdbc -> jdbc.update("UPDATE ota_firmware SET firmware_version='tampered' WHERE id=?", id)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app(first, jdbc -> jdbc.update("DELETE FROM ota_firmware WHERE id=?", id)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app(first, jdbc -> jdbc.update("UPDATE ota_firmware SET status='READY' WHERE id=?", id)))
                .isInstanceOf(DataAccessException.class);
    }

    /** 完整键集分页应无重复无遗漏，并拒绝不能解释的游标。 */
    @Test
    void paginatesAndRejectsInvalidCursor() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        for (int i = 0; i < 3; i++) {
            ok(write(fixture, base(fixture), UUID.randomUUID().toString(), body(fixture, "v" + i)));
        }
        JsonNode first = ok(read(fixture, base(fixture) + "?limit=2"));
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        JsonNode second = ok(read(fixture, base(fixture) + "?limit=2&cursor="
                + java.net.URLEncoder.encode(first.path("nextCursor").asText(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("items").get(0).path("id").asText())
                .isNotIn(first.path("items").get(0).path("id").asText(), first.path("items").get(1).path("id").asText());
        assertThat(read(fixture, base(fixture) + "?cursor=invalid").getResponse().getStatus()).isEqualTo(400);
    }

    /** 两个真实事务取消同一修订只推进一次，返回同一个微秒终态。 */
    @Test
    void concurrentCancellationCommitsOneTerminalFact() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        JsonNode created = ok(write(fixture, base(fixture), UUID.randomUUID().toString(), body(fixture, "1")));
        String path = base(fixture) + "/" + created.path("id").asText() + "/cancel";
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> ok(write(fixture, path, UUID.randomUUID().toString(),
                    "{\"expectedRevision\":\"0\"}")));
            var second = executor.submit(() -> ok(write(fixture, path, UUID.randomUUID().toString(),
                    "{\"expectedRevision\":\"0\"}")));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
    }

    /** 持久审计失败必须回滚固件与创建映射，而不是留下无审计的成功事实。 */
    @Test
    void auditFailureRollsBackCreationAndReplayIdentity() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        JdbcTemplate owner = owner();
        String function = "ota_test_audit_failure_" + fixture.projectId().toString().replace("-", "");
        owner.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.project_id = '" + fixture.projectId() + "'::uuid THEN RAISE EXCEPTION 'test audit failure'; END IF; "
                + "RETURN NEW; END; $$");
        try {
            owner.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON sys_audit_log FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");
            assertThat(write(fixture, base(fixture), UUID.randomUUID().toString(), body(fixture, "1"))
                    .getResponse().getStatus()).isEqualTo(500);
            assertThat(owner.queryForObject("SELECT count(*) FROM ota_firmware WHERE project_id=?", Long.class,
                    fixture.projectId())).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM ota_firmware_creation_request WHERE project_id=?", Long.class,
                    fixture.projectId())).isZero();
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS " + function + " ON sys_audit_log");
            owner.execute("DROP FUNCTION " + function + "()");
        }
    }

    /** 同一事务绑定普通app角色的项目RLS，不能通过owner连接证明隔离。 */
    private static <T> T app(Fixture fixture, Function<JdbcTemplate, T> work) {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, fixture.tenantId().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, fixture.projectId().toString());
            return work.apply(jdbc);
        });
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'{noop}unused','OTA测试',now())",
                    account, account + "@example.invalid");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), account);
        }
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA测试项目','sh-1',?)",
                fixture.projectId(), fixture.tenantId(), "ota_" + fixture.projectId().toString().replace("-", ""));
        UUID ownerId = role == ProjectRole.OWNER ? fixture.accountId() : fixture.ownerId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.projectId(), ownerId);
        if (role != ProjectRole.OWNER) {
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId(), role.name());
        }
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA测试类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(), "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 原样拼接固定安全fixture UUID，保留版本反例中的JSON词法。 */
    private static String body(Fixture fixture, String version) {
        return "{\"deviceTypeId\":\"" + fixture.typeId() + "\",\"thingModelVersionId\":\""
                + fixture.modelId() + "\",\"firmwareVersion\":\"" + version + "\"}";
    }

    /** 唯一冻结资源根，不扩展到设备原生程序。 */
    private static String base(Fixture fixture) {
        return "/api/v1/projects/" + fixture.projectId() + "/ota/firmwares";
    }

    /** JWT仍经生产验签及角色读取。 */
    private MvcResult request(Fixture fixture, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(
                new AuthenticatedPrincipal(fixture.accountId(), fixture.tenantId(), fixture.projectId())).value())).andReturn();
    }

    /** 可省略key以验证必填边界。 */
    private MvcResult write(Fixture fixture, String path, String key, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request(fixture, request);
    }

    /** 读取始终走当前认证范围。 */
    private MvcResult read(Fixture fixture, String path) throws Exception {
        return request(fixture, get(path));
    }

    /** 成功体为直接业务对象，保留失败正文方便首因诊断。 */
    private static JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isBetween(200, 299);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** 固定错误结构不靠单一HTTP状态掩盖业务失败原因。 */
    private static void error(MvcResult result, int status, int code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(code);
    }

    /** 每例全部归属，确保失败后也能精确清理。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
