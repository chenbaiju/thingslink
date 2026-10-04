package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.support.audit.AuditQueryService;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实PG与真实JWT验证S13-4c-1 OTA审计只读时间线。
 *
 * <p>审计事实不靠测试代码直接INSERT：先经真实HTTP创建/取消固件草稿，让生产
 * {@code AuditLogService} 写出 {@code ota.firmware.*} 行，再读接口核对同一批事实。
 * 非OTA事实（成员邀请）同样经项目API写出，用来证明前缀收窄真的生效，而不是
 * 恰好没有别的模块审计行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaAuditReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 端口边界之外直接验证前缀语义与fail-closed，HTTP层无法注入通配符。 */
    @Autowired private AuditQueryService auditQuery;
    /** 响应只作断言。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例自有项目图；同租户可追加第二个项目以验证项目维度不可见。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 真实固件草稿写入的审计行最新在前，并且limit=1续页不重不漏。 */
    @Test
    void listsOtaFirmwareAuditNewestFirstAndPaginatesWithoutDuplicates() throws Exception {
        Fixture own = seed();
        UUID firmware = draft(own, "audit-v1");
        cancel(own, firmware);

        JsonNode page = ok(send(own, "GET", audits(own), null, null), 200);
        JsonNode items = page.path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        // 取消发生在创建之后，排序键是(created_at DESC,id DESC)。
        assertThat(actions(items)).containsExactly("ota.firmware.cancelled", "ota.firmware.created");

        for (JsonNode row : items) {
            assertThat(row.path("targetType").asText()).isEqualTo("ota_firmware");
            assertThat(row.path("actorAccountId").asText()).isEqualTo(own.account().toString());
            // 真实写入器把固件身份放在target_id，details只带status/revision；
            // 因此"这行指向哪个固件"由targetId断言，details断言非空且仍是结构化对象。
            assertThat(row.path("targetId").asText()).isEqualTo(firmware.toString());
            assertThat(row.path("details").isObject()).isTrue();
            assertThat(row.path("details").size()).isPositive();
            assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "actorAccountId", "targetType",
                    "targetId", "action", "traceId", "details", "createdAt");
        }
        assertThat(items.get(0).path("details").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(items.get(1).path("details").path("status").asText()).isEqualTo("DRAFT");

        JsonNode first = ok(send(own, "GET", audits(own) + "?limit=1", null, null), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(ids(first.path("items"))).containsExactly(items.get(0).path("id").asText());

        JsonNode second = ok(send(own, "GET",
                audits(own) + "?limit=1&cursor=" + first.path("nextCursor").asText(), null, null), 200);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(ids(second.path("items"))).containsExactly(items.get(1).path("id").asText());
        assertThat(ids(second.path("items"))).doesNotContainAnyElementsOf(ids(first.path("items")));

        // 游标是不可信输入：解不开或跨项目复用都必须判为参数不合法，而不是空页。
        error(send(own, "GET", audits(own) + "?cursor=not-a-cursor", null, null), 400, 10001);
        error(send(own, "GET", audits(own) + "?cursor="
                + Cursor.encode(own.project() + "|not-a-time|" + Uuid7.generate()), null, null), 400, 10001);
    }

    /** action精确过滤只返回该动作，且同项目的非OTA审计行永远不出现在OTA时间线里。 */
    @Test
    void filtersByExactOtaActionAndNeverLeaksOtherModules() throws Exception {
        Fixture own = seed();
        draft(own, "audit-v1");
        UUID firmware = draft(own, "audit-v2");
        cancel(own, firmware);
        invite(own);

        // 前提：非OTA审计行确实写进了同一项目，否则"没返回"不能证明是前缀收窄生效。
        assertThat(owner().queryForObject(
                "SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.member.invited'",
                Long.class, own.project())).isEqualTo(1);

        JsonNode all = ok(send(own, "GET", audits(own), null, null), 200);
        assertThat(actions(all.path("items"))).allMatch(action -> action.startsWith("ota."));
        assertThat(actions(all.path("items"))).doesNotContain("project.member.invited");
        assertThat(actions(all.path("items"))).contains("ota.firmware.created", "ota.firmware.cancelled");

        JsonNode filtered = ok(send(own, "GET", audits(own) + "?action=ota.firmware.created", null, null), 200);
        assertThat(actions(filtered.path("items"))).containsExactly("ota.firmware.created",
                "ota.firmware.created");
        assertThat(actions(filtered.path("items"))).doesNotContain("ota.firmware.cancelled");
    }

    /** 同一租户的另一个项目审计行不可见，跨项目游标同样被拒。 */
    @Test
    void hidesOtherProjectAuditFactsInTheSameTenant() throws Exception {
        Fixture own = seed();
        Fixture sibling = siblingProject(own);
        UUID ownFirmware = draft(own, "own-v1");
        UUID foreignFirmware = draft(sibling, "sibling-v1");

        assertThat(owner().queryForObject(
                "SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'ota.%'",
                Long.class, sibling.project())).isPositive();

        JsonNode page = ok(send(own, "GET", audits(own), null, null), 200);
        assertThat(actions(page.path("items"))).containsExactly("ota.firmware.created");
        assertThat(page.path("items").get(0).path("targetId").asText()).isEqualTo(ownFirmware.toString());
        assertThat(page.path("items").get(0).path("targetId").asText())
                .isNotEqualTo(foreignFirmware.toString());

        error(send(own, "GET", audits(own) + "?cursor="
                + Cursor.encode(sibling.project() + "|" + Instant.now() + "|" + Uuid7.generate()), null, null),
                400, 10001);
    }

    /** 非部署角色是70049；命名空间外动作与越界分页是参数错误。 */
    @Test
    void rejectsMembersWithoutDeployRoleAndRejectsMalformedNamespace() throws Exception {
        Fixture own = seed();
        draft(own, "audit-v1");
        ok(send(own, "GET", audits(own), null, null), 200);

        for (String role : List.of("OPERATOR", "VIEWER")) {
            owner().update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",
                    role, own.project(), own.account());
            error(send(own, "GET", audits(own), null, null), 403, 70049);
        }
        owner().update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        ok(send(own, "GET", audits(own), null, null), 200);

        // 命名空间外动作必须按格式错误拒绝，而不是被前缀过滤成空页后返回200。
        error(send(own, "GET", audits(own) + "?action=device.updated", null, null), 400, 10002);
        error(send(own, "GET", audits(own) + "?action=ota.", null, null), 400, 10002);
        error(send(own, "GET", audits(own) + "?limit=0", null, null), 400, 10001);
        error(send(own, "GET", audits(own) + "?limit=101", null, null), 400, 10001);
    }

    /** 前缀按字面字符匹配，且tenant/project缺失与越界条数一律fail-closed。 */
    @Test
    void treatsActionPrefixAsLiteralAndFailsClosedOnMissingScope() throws Exception {
        Fixture own = seed();
        assertThatThrownBy(() -> auditQuery.page(null, own.project(), "ota.", null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
        assertThatThrownBy(() -> auditQuery.page(own.tenant(), null, "ota.", null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
        assertThatThrownBy(() -> auditQuery.page(own.tenant(), own.project(), "ota.", null, null, 101))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));

        draft(own, "literal-v1");
        // 若"%"与"_"未转义，它们会变成通配符并匹配到刚写入的审计行。
        assertThat(auditQuery.page(own.tenant(), own.project(), "%", null, null, 20).items()).isEmpty();
        assertThat(auditQuery.page(own.tenant(), own.project(), "_", null, null, 20).items()).isEmpty();
        assertThat(auditQuery.page(own.tenant(), own.project(), "ota.firmware.", null, null, 20).items())
                .hasSize(1);
    }

    /** 创建草稿走真实领域入口，审计由生产写入端口产生。 */
    private UUID draft(Fixture f, String version) throws Exception {
        String body = "{\"deviceTypeId\":\"" + f.type() + "\",\"thingModelVersionId\":\"" + f.model()
                + "\",\"firmwareVersion\":\"" + version + "\"}";
        JsonNode created = ok(send(f, "POST", firmwares(f), key(), body.getBytes(StandardCharsets.UTF_8)), 201);
        return UUID.fromString(created.path("id").asText());
    }

    /** 草稿取消产生第二条真实审计事实。 */
    private void cancel(Fixture f, UUID firmware) throws Exception {
        ok(send(f, "POST", firmwares(f) + "/" + firmware + "/cancel", key(),
                "{\"expectedRevision\":\"0\"}".getBytes(StandardCharsets.UTF_8)), 200);
    }

    /** 经项目API邀请成员，写出同项目同租户的非OTA审计行。 */
    private void invite(Fixture f) throws Exception {
        String body = "{\"email\":\"" + f.invitee() + "@example.invalid\",\"role\":\"VIEWER\"}";
        ok(send(f, "POST", "/api/v1/projects/" + f.project() + "/members", null,
                body.getBytes(StandardCharsets.UTF_8)), 200);
    }

    /** 响应动作编码按响应顺序。 */
    private static List<String> actions(JsonNode items) {
        return StreamSupport.stream(items.spliterator(), false).map(row -> row.path("action").asText()).toList();
    }

    /** 响应审计ID按响应顺序。 */
    private static List<String> ids(JsonNode items) {
        return StreamSupport.stream(items.spliterator(), false)
                .map(row -> row.path("id").asText()).toList();
    }

    /** 固件集合路径。 */
    private static String firmwares(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/firmwares";
    }

    /** 审计集合路径。 */
    private static String audits(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/audits";
    }

    /** 唯一请求键。 */ private static String key() { return UUID.randomUUID().toString(); }

    /** 真实HTTP/1.1往返，JWT经过生产验签与数据库角色读取。 */
    private HttpResponse<String> send(Fixture f, String method, String path, String key, byte[] body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45));
        if (f != null) {
            builder.header("Authorization", "Bearer " + tokens.issue(
                    new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value());
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 明确HTTP状态并保留响应首因。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** HTTP状态和领域码均须匹配。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }

    /** 新项目独立租户与账号，类型与模型满足OTA草稿资格。 */
    private Fixture seed() {
        UUID tenant = Uuid7.generate(), account = Uuid7.generate(), invitee = Uuid7.generate();
        UUID project = Uuid7.generate(), type = Uuid7.generate(), model = Uuid7.generate();
        Fixture fixture = new Fixture(tenant, account, invitee, project, type, model);
        fixtures.add(fixture);
        JdbcTemplate jdbc = owner();
        for (UUID id : List.of(account, invitee)) {
            jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)"
                    + " VALUES (?,?,'{noop}unused','OTA审计测试',now())", id, id + "@example.invalid");
        }
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA审计租户')", tenant);
        jdbc.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", tenant);
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), tenant, account);
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA审计项目','sh-1',?)",
                project, tenant, "otaaudit_" + project.toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), project, account);
        modelAndType(fixture);
        return fixture;
    }

    /** 同租户同账号的第二个项目，用来证明项目维度而非租户维度在收窄。 */
    private Fixture siblingProject(Fixture base) {
        UUID project = Uuid7.generate(), type = Uuid7.generate(), model = Uuid7.generate();
        Fixture fixture = new Fixture(base.tenant(), base.account(), base.invitee(), project, type, model);
        fixtures.add(fixture);
        JdbcTemplate jdbc = owner();
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA审计兄弟项目','sh-1',?)",
                project, base.tenant(), "otaaudit_" + project.toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), project, base.account());
        modelAndType(fixture);
        return fixture;
    }

    /** 已发布类型与不可变模型版本是固件草稿的真实前置条件。 */
    private void modelAndType(Fixture f) {
        JdbcTemplate jdbc = owner();
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA审计类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, f.type(), f.tenant(), f.project(), "type_" + f.type(), "product_" + f.type());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, f.model(), f.tenant(), f.project(), f.type(), "a".repeat(64));
    }

    /** owner连接仅用于准备、观察与清理；读取断言一律走真实HTTP。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 先删全部项目级事实，再删共享的租户与账号，避免兄弟项目的外键残留。 */
    @AfterEach
    void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
            for (Fixture fixture : fixtures) {
                for (String table : List.of("ota_firmware_creation_request", "ota_firmware_upload_session",
                        "ota_firmware")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
                owner.update("DELETE FROM dev_thing_model_version WHERE project_id=?", fixture.project());
                owner.update("DELETE FROM dev_type WHERE project_id=?", fixture.project());
                owner.update("DELETE FROM sys_project_member WHERE project_id=?", fixture.project());
                owner.update("DELETE FROM sys_project WHERE id=?", fixture.project());
            }
            for (Fixture fixture : fixtures) {
                owner.update("DELETE FROM sys_tenant_member WHERE tenant_id=?", fixture.tenant());
                owner.update("DELETE FROM sys_tenant WHERE id=?", fixture.tenant());
                owner.update("DELETE FROM sys_account WHERE id IN (?,?)", fixture.account(), fixture.invitee());
            }
            // sys_audit_log刻意不可删（触发器禁止UPDATE/DELETE），审计事实按项目ID天然隔离。
        });
        fixtures.clear();
    }

    /** 本例项目图；两个项目共享租户与账号时仍必须互不可见。
     * @param tenant 本例租户
     * @param account 被测请求账号
     * @param invitee 非OTA审计行的目标账号
     * @param project 本项目
     * @param type 已发布类型
     * @param model 不可变模型版本
     */
    private record Fixture(UUID tenant, UUID account, UUID invitee, UUID project, UUID type, UUID model) { }
}
