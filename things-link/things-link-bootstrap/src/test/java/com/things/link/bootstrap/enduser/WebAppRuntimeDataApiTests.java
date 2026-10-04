package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S12-2b1：真实App JWT、普通RLS、五条数据HTTP及动态权限反例。 */
@AutoConfigureMockMvc
@Import(WebAppRuntimeDataApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"RUNTIME_POSTGRES"})
class WebAppRuntimeDataApiTests extends AbstractIntegrationTest {
    /** 专库隔离发布历史与故障夹具，既有共享Bootstrap测试无需增加清理依赖。 */
    private static final PostgreSQLContainer<?> RUNTIME_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_data_api").withUsername("thingslink").withPassword("thingslink");
    /** Spring连接池及Flyway创建前启动专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 逐字段检查原始JSON，避免DTO反序列化悄悄忽略泄漏字段。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 完整App过滤链及MVC入口。 */
    @Autowired private MockMvc mvc;
    /** 真正App签名器，issuer/secret与Console分离。 */
    @Autowired private AppTokenIssuer appTokens;
    /** Console令牌只用于证明它不能冒充App身份。 */
    @Autowired private TokenIssuer consoleTokens;
    /** 应用数据源用于确认没有用owner执行HTTP。 */
    @Autowired private JdbcTemplate application;
    /** 基类quota runner固定共享库；本App只读测试既不需要它，也不能误改共享库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedSharedQuotaRunner;
    /** Owner只建立与修改当前用例的发布夹具。 */
    private JdbcTemplate owner;
    /** 每例唯一租户、项目、App用户、应用与两个精确看板版本。 */
    private Fixture f;
    /** 完整数据Schema和真实模型/设备事实。 */
    private DataFixture data;

    /** 不共享用户或全表DELETE；所有事实由独占容器最终回收。 */
    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL, "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_data_api");
        data = WebAppDataRuntimeFixture.seed(owner);
        f = data.runtime();
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", f.tenantId());
    }

    /** 真正签名JWT/当前Schema/grant/绑定/PG源版本进入同一完整HTTP读取链。 */
    @Test void returnsModelSnapshotAndAuthoritativeSparseValues() throws Exception {
        JsonNode snapshot = success(request(post("/api/v1/app/devices/snapshots/query").content(snapshotBody(data.first()))));
        assertThat(snapshot.propertyNames()).containsExactlyInAnyOrder("devices", "models");
        assertThat(snapshot.path("devices").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        JsonNode description = snapshot.path("models").get(0);
        assertThat(description.path("digest").asString()).isEqualTo(data.digest());
        assertThat(description.path("properties")).hasSize(1);
        assertThat(description.path("properties").get(0).path("propertyKey").asString()).isEqualTo("temperature");
        assertThat(description.toString()).doesNotContain("secret", "events", "commands");
        JsonNode absent = success(request(post("/api/v1/app/devices/current-values/query").content(currentBody(data.first()))));
        assertThat(absent.path("devices").get(0).path("values").get(0).path("state").asString()).isEqualTo("NO_VALUE");
        owner.update("""
                INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version)
                VALUES (?,?,?,'{"temperature":23.5}',
                    '{"temperature":"2026-09-06T12:00:00.123456Z"}',?::jsonb)
                """, data.first(), f.tenantId(), f.projectId(), "{\"temperature\":\"" + data.model() + "\"}");
        JsonNode values = success(request(post("/api/v1/app/devices/current-values/query").content(currentBody(data.first()))));
        JsonNode value = values.path("devices").get(0).path("values").get(0);
        assertThat(value.path("state").asString()).isEqualTo("VALUE");
        assertThat(value.path("value").doubleValue()).isEqualTo(23.5);
        assertThat(value.path("reportedModelVersionId").asString()).isEqualTo(data.model().toString());
        assertThat(value.path("occurredAt").asString()).isEqualTo("2026-09-06T12:00:00.123456Z");
    }

    /** 目录在绑定、软删除和模型过滤之后取页；更近期的48个不可用候选不能挤掉真实第二页。 */
    @Test void catalogFiltersBeforePaginationAndBindsCursorToActorAndFilter() throws Exception {
        for (int i = 0; i < 16; i++) {
            WebAppDataRuntimeFixture.addDevice(owner, f, data.type(), data.model(), false, Instant.parse("2026-09-06T13:00:00Z"));
            WebAppDataRuntimeFixture.addDevice(owner, f, data.type(), null, true, Instant.parse("2026-09-06T13:00:00Z"));
            UUID deleted = WebAppDataRuntimeFixture.addDevice(owner, f, data.type(), data.model(), true, Instant.parse("2026-09-06T13:00:00Z"));
            owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", deleted);
        }
        JsonNode first = success(request(catalog().queryParam("limit", "1")));
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("items").get(0).path("deviceId").asString()).isEqualTo(data.first().toString());
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asString();
        JsonNode next = success(request(catalog().queryParam("limit", "1").queryParam("cursor", cursor)));
        assertThat(next.path("items")).hasSize(1);
        assertThat(next.path("items").get(0).path("deviceId").asString()).isEqualTo(data.second().toString());
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(next.path("nextCursor").isNull()).isTrue();
        error(request(catalog().queryParam("limit", "2").queryParam("cursor", cursor)), 400, 10001);
        error(request(catalog().queryParam("limit", "1").queryParam("cursor", cursor + "x")), 400, 10001);
        error(request(catalog().queryParam("cursor", "")), 400, 10001);
        UUID otherUser = UUID.randomUUID();
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?,?,?,'test-only','ACTIVE')",
                otherUser, f.tenantId(), "user_" + otherUser.toString().replace("-", ""));
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status) VALUES (?,?,?,?,'OBSERVER','ACTIVE')",
                UUID.randomUUID(), f.tenantId(), f.projectId(), otherUser);
        owner.update("""
                INSERT INTO app_user_dashboard(id,tenant_id,project_id,app_user_id,dashboard_id,status,revision,
                    created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,'ACTIVE',1,now(),now(),?,?)
                """, UUID.randomUUID(), f.tenantId(), f.projectId(), otherUser, f.authorized().dashboardId(), f.actorId(), f.actorId());
        String otherToken = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), otherUser, 0)).value();
        error(mvc.perform(catalog().queryParam("limit", "1").queryParam("cursor", cursor)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .header("X-Application-Key", f.appKey()).header("X-Application-Version", f.applicationVersionId().toString())
                .header("X-Application-Revision", "2").header("X-Dashboard-Version", f.authorized().dashboardVersionId().toString()))
                .andReturn(), 400, 10001);

    }

    /** 当前合法运行上下文不能授权未声明属性或其他模型；不允许退回旧S11任意查询。 */
    @Test void refusesKeysAndModelsOutsideAuthorizedSchema() throws Exception {
        error(request(post("/api/v1/app/devices/current-values/query")
                .content(currentBody(data.first()).replace("temperature", "secret"))), 400, 10001);
        error(request(get("/api/v1/app/devices/catalog").queryParam("modelVersionId", UUID.randomUUID().toString())), 400, 10001);
        error(request(history("MAX")), 400, 10001);
    }

    /** 所有五条数据入口使用真实当前用户/角色/pgv/grant；错误与成功一样禁止缓存。 */
    @Test void rejectsAnonymousConsoleTokensAndMissingOrDuplicateRuntimeHeaders() throws Exception {
        String console = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        for (MockHttpServletRequestBuilder call : List.of(catalog(), history(),
                post("/api/v1/app/devices/snapshots/query").content(snapshotBody(data.first())),
                post("/api/v1/app/devices/current-values/query").content(currentBody(data.first())),
                post("/api/v1/app/alarms/query").content(alarmBody(data.first())))) {
            MvcResult anonymous = mvc.perform(call.contentType(MediaType.APPLICATION_JSON)).andReturn();
            assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
            noStore(anonymous);
        }
        MvcResult wrongActor = mvc.perform(catalog().header(HttpHeaders.AUTHORIZATION, "Bearer " + console)).andReturn();
        assertThat(wrongActor.getResponse().getStatus()).isEqualTo(401);
        noStore(wrongActor);
        String token = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        error(mvc.perform(catalog().header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        error(request(catalog().header("X-Application-Revision", "2")), 400, 10001);
        error(request(catalog().header("X-Application-Key", f.appKey() + "," + f.appKey())), 400, 10001);
    }

    /** POST查询每次重读绑定和grant，公共幂等键不能让第二次返回旧成功。 */
    @Test void sameIdempotencyKeyDoesNotReplayAfterBindingOrGrantRevocation() throws Exception {
        String path = "/api/v1/app/devices/snapshots/query";
        success(request(post(path).content(snapshotBody(data.first())).header("Idempotency-Key", "data-query-same")));
        owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=? AND device_id=?",
                f.appUserId(), data.first());
        JsonNode hidden = success(request(post(path).content(snapshotBody(data.first())).header("Idempotency-Key", "data-query-same")));
        assertThat(hidden.path("devices").get(0).propertyNames()).containsExactlyInAnyOrder("deviceId", "status");
        assertThat(hidden.path("devices").get(0).path("status").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(hidden.path("models")).isEmpty();
        owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=2,revoked_at=now(),updated_at=now(),revoked_by=?,updated_by=? WHERE app_user_id=?",
                f.actorId(), f.actorId(), f.appUserId());
        error(request(post(path).content(snapshotBody(data.second())).header("Idempotency-Key", "data-query-same")), 404, 60023);
    }

    /** 非绑定设备的严格history/alarm路径拒绝整次请求；快照/当前值保留显式不可用状态。 */
    @Test void hiddenDevicesCannotLeakHistoryOrAlarmResults() throws Exception {
        owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=? AND device_id=?",
                f.appUserId(), data.first());
        error(request(history()), 400, 10001);
        error(request(post("/api/v1/app/alarms/query").content(alarmBody(data.first()))), 404, 60010);
        JsonNode current = success(request(post("/api/v1/app/devices/current-values/query").content(currentBody(data.first()))));
        assertThat(current.path("devices").get(0).path("status").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(current.path("devices").get(0).path("values")).isEmpty();
    }

    /** 同批模型失配不能先于另一设备不可见返回，两个排列都应隐藏全部告警结果。 */
    @Test void alarmVisibilityPrecedesEveryModelMismatchRegardlessOfOrder() throws Exception {
        UUID mismatch = WebAppDataRuntimeFixture.addDevice(owner, f, data.type(), null, true, Instant.now());
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", data.second());
        for (List<UUID> order : List.of(List.of(mismatch, data.second()), List.of(data.second(), mismatch))) {
            var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(alarmBody(order.getFirst()));
            input.withArray("/devices").addObject().put("deviceId", order.getLast().toString())
                    .put("expectedModelVersionId", data.model().toString());
            error(request(post("/api/v1/app/alarms/query").content(input.toString())), 404, 60010);
        }
    }

    /** 合法空历史和空告警返回显式空集合；请求结束时刻为排他值且没有缓存条件短路。 */
    @Test void servesEmptyVersionedHistoryAndFilteredAlarmPage() throws Exception {
        JsonNode history = success(request(history().header(HttpHeaders.IF_NONE_MATCH, "old")));
        assertThat(history.path("requestedGranularity").asString()).isEqualTo("RAW");
        assertThat(history.path("points")).isEmpty();
        JsonNode alarms = success(request(post("/api/v1/app/alarms/query").content(alarmBody(data.first()))));
        assertThat(alarms.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(alarms.path("items")).isEmpty();
        assertThat(alarms.path("nextCursor").isNull()).isTrue();
        assertThat(alarms.path("hasMore").asBoolean()).isFalse();
    }

    /** 归档仅保留查询，旧代次和停用身份在下次读取失败，不能重用之前的Schema资格。 */
    @Test void archivedReadsRemainAvailableButStaleGenerationFails() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        success(request(post("/api/v1/app/devices/snapshots/query").content(snapshotBody(data.first()))));
        success(request(post("/api/v1/app/devices/current-values/query").content(currentBody(data.first()))));
        success(request(post("/api/v1/app/alarms/query").content(alarmBody(data.first()))));
        owner.update("UPDATE sys_project SET lifecycle_generation=lifecycle_generation+1 WHERE id=?", f.projectId());
        error(request(catalog()), 401, 60009);
    }

    /** DTO之外的未知或重复JSON/query字段和非法UUID不能被宽松反序列化吸收。 */
    @Test void rejectsUnknownAndDuplicateInputs() throws Exception {
        String body = currentBody(data.first());
        error(request(post("/api/v1/app/devices/current-values/query").content("{\"scope\":1," + body.substring(1))), 400, 10001);
        error(request(post("/api/v1/app/devices/current-values/query").content("{\"devices\":[],\"devices\":[]}")), 400, 10001);
        error(request(catalog().queryParam("scope", "tenant")), 400, 10001);
        error(request(catalog().queryParam("limit", "1", "2")), 400, 10001);
    }

    /** 批量摘要不受事故页限制，ACK仍活动，PENDING/CLEARED不计入。 */
    @Test void consoleAlarmStatusCoversEveryRequestedDevice() throws Exception {
        String token = alarmStatusToken();
        for (int index = 0; index < 52; index++) seedStatusAlarm(data.first(), "ACTIVE", index == 0);
        seedStatusAlarm(data.second(), "PENDING", false);
        seedStatusAlarm(data.second(), "CLEARED", false);
        var response = success(alarmStatus(token, data.first(), data.second()));
        assertThat(response.path("devices")).hasSize(2);
        assertThat(response.path("devices").get(0).path("state").asString()).isEqualTo("ACTIVE");
        assertThat(response.path("devices").get(1).path("state").asString()).isEqualTo("NORMAL");
        assertThat(response.path("devices").get(1).path("modelVersionId").asString()).isEqualTo(data.model().toString());
        assertThat(Instant.parse(response.path("observedAt").asString())).isBeforeOrEqualTo(Instant.now());
        owner.update("UPDATE alarm_instance SET condition_state='CLEARED',cleared_at=now(),clear_reason='AUTO_RECOVERY' "
                + "WHERE originator_id=? AND ack_state='UNACKNOWLEDGED'", data.first());
        assertThat(success(alarmStatus(token, data.first())).path("devices").get(0).path("state").asString()).isEqualTo("ACTIVE");
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        success(alarmStatus(token, data.first(), data.second()));
    }

    @Test void consoleAlarmStatusRejectsInvalidDevicesModelsAndMembership() throws Exception {
        String token = alarmStatusToken();
        success(alarmStatus(token, data.first(), data.second()));
        UUID unbound = WebAppDataRuntimeFixture.addDevice(owner, f, data.type(), null, false, Instant.now());
        consoleCatalogError(alarmStatus(token, data.first(), unbound), 400, 10001);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", data.second());
        consoleCatalogError(alarmStatus(token, data.first(), data.second()), 404, 30020);
        var other = WebAppDataRuntimeFixture.seed(owner);
        consoleCatalogError(alarmStatus(token, other.first()), 404, 30020);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.actorId());
        consoleCatalogError(alarmStatus(token, data.first()), 401, 20020);
    }

    @Test void consoleAlarmStatusQueryIsClosedBoundedAndIdentitySpecific() throws Exception {
        String token = alarmStatusToken();
        consoleCatalogError(alarmStatus(token, data.first(), data.first()), 400, 10001);
        consoleCatalogError(alarmStatus(token, java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new)), 400, 10001);
        for (String value : List.of("1-1-1-1-1", data.first() + "," + data.second(), " " + data.first(), "")) {
            consoleCatalogError(mvc.perform(get(alarmStatusPath()).queryParam("deviceId", value)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        }
        consoleCatalogError(mvc.perform(get(alarmStatusPath()).queryParam("deviceId", data.first().toString())
                .queryParam("extra", "1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        consoleCatalogError(mvc.perform(get(alarmStatusPath()).queryParam("deviceId", data.first().toString())).andReturn(), 401, null);
        String app = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        consoleCatalogError(alarmStatus(app, data.first()), 401, null);
    }

    /** JWT所属租户故意不同，服务端必须依据项目归属建立普通RLS范围。 */
    private String alarmStatusToken() {
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        UUID otherTenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'摘要协作者')", otherTenant);
        return consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), otherTenant, f.projectId())).value();
    }
    private String alarmStatusPath() { return "/api/v1/projects/" + f.projectId() + "/alarms/device-status"; }
    private MvcResult alarmStatus(String token, UUID... ids) throws Exception {
        return mvc.perform(get(alarmStatusPath()).queryParam("deviceId", java.util.Arrays.stream(ids)
                .map(UUID::toString).toArray(String[]::new)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }
    private void seedStatusAlarm(UUID device, String state, boolean acknowledged) {
        UUID rule = UUID.randomUUID();
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,
                    property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,?,?,?,'temperature','GT',30,'LT',25,'WARNING')
                """, rule, f.tenantId(), f.projectId(), rule.toString(), rule.toString(), device);
        owner.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                    alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,
                    cleared_at,clear_reason,acknowledged_at,acknowledged_by)
                VALUES (?,?,?,?,'DEVICE',?,?,'WARNING',?,?,now(),now(),now(),31,?,?,?,?)
                """, UUID.randomUUID(), f.tenantId(), f.projectId(), rule, device, rule.toString(), state,
                acknowledged ? "ACKNOWLEDGED" : "UNACKNOWLEDGED",
                state.equals("CLEARED") ? java.sql.Timestamp.from(Instant.now()) : null,
                state.equals("CLEARED") ? "AUTO_RECOVERY" : null,
                acknowledged ? java.sql.Timestamp.from(Instant.now()) : null, acknowledged ? f.actorId() : null);
    }

    /** Console三条同核心读取使用项目成员身份，即使无App设备绑定也可读取归档项目。 */
    @Test void consoleDataReadsUseMembershipAndRemainReadOnlyWhenArchived() throws Exception {
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=?", f.appUserId());
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        String prefix = "/api/v1/projects/" + f.projectId();
        for (MockHttpServletRequestBuilder call : List.of(
                post(prefix + "/devices/snapshots/query").content(snapshotBody(data.first())),
                post(prefix + "/devices/current-value-snapshots/query").content(currentBody(data.first())),
                post(prefix + "/alarms/query").content(alarmBody(data.first())))) {
            JsonNode body = success(mvc.perform(call.contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "console-query-key")).andReturn());
            if (body.has("devices")) assertThat(body.path("devices").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        }
    }

    /** 跨租户协作者的JWT租户不能覆盖受邀项目的真实租户；三条Console查询均使用项目归属。 */
    @Test void consoleCollaboratorReadsProjectOwnerScope() throws Exception {
        UUID collaboratorTenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'协作者所属租户')", collaboratorTenant);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), collaboratorTenant, f.projectId())).value();
        String prefix = "/api/v1/projects/" + f.projectId();
        JsonNode snapshot = success(mvc.perform(post(prefix + "/devices/snapshots/query")
                .contentType(MediaType.APPLICATION_JSON).content(snapshotBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
        assertThat(snapshot.path("devices").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        JsonNode current = success(mvc.perform(post(prefix + "/devices/current-value-snapshots/query")
                .contentType(MediaType.APPLICATION_JSON).content(currentBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
        assertThat(current.path("devices").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        success(mvc.perform(post(prefix + "/alarms/query").contentType(MediaType.APPLICATION_JSON).content(alarmBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
    }

    /** Console公开查询不能接受App JWT，也不能绕过被撤销的项目成员关系。 */
    @Test void consoleReadRejectsAppIdentityAndRevokedMembership() throws Exception {
        String path = "/api/v1/projects/" + f.projectId() + "/devices/snapshots/query";
        String app = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        MvcResult wrong = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(snapshotBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + app)).andReturn();
        error(wrong, 401, 20020);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        success(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(snapshotBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.actorId());
        MvcResult denied = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(snapshotBody(data.first()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        error(denied, 401, 20020);
    }

    /** Console目录使用真实成员/项目tenant，模型过滤后分页且归档可读，不依赖App绑定。 */
    @Test
    void consoleCatalogUsesExactModelPaginationAndAuthoritativeTenant() throws Exception {
        UUID collaboratorTenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'目录协作者租户')", collaboratorTenant);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=?", f.appUserId());
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), collaboratorTenant, f.projectId())).value();
        String path = "/api/v1/projects/" + f.projectId() + "/devices/catalog";
        MvcResult response = mvc.perform(get(path).queryParam("modelVersionId", data.model().toString())
                .queryParam("limit", "1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        JsonNode first = success(response);
        assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("items").get(0).propertyNames())
                .containsExactlyInAnyOrder("deviceId", "name", "deviceStatus", "currentModelVersionId");
        assertThat(first.path("items").get(0).path("currentModelVersionId").asString()).isEqualTo(data.model().toString());
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asString();
        JsonNode second = success(mvc.perform(get(path).queryParam("modelVersionId", data.model().toString())
                .queryParam("limit", "1").queryParam("cursor", cursor).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
        assertThat(second.path("items").get(0).path("deviceId").asString())
                .isNotEqualTo(first.path("items").get(0).path("deviceId").asString());
        consoleCatalogError(mvc.perform(get(path).queryParam("modelVersionId", data.model().toString()).queryParam("limit", "2")
                .queryParam("cursor", cursor).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        JsonNode empty = success(mvc.perform(get(path).queryParam("modelVersionId", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
    }

    /** 目录不接收AppBearer，query封闭，成员移除后不能继续使用旧Console令牌。 */
    @Test
    void consoleCatalogRejectsWrongIdentityUnknownQueryAndRevokedMembership() throws Exception {
        String path = "/api/v1/projects/" + f.projectId() + "/devices/catalog";
        String app = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        consoleCatalogError(mvc.perform(get(path).queryParam("modelVersionId", data.model().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + app)).andReturn(), 401, null);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        for (MockHttpServletRequestBuilder request : List.of(
                get(path).queryParam("modelVersionId", data.model().toString()).queryParam("extra", "1"),
                get(path).queryParam("modelVersionId", data.model().toString()).queryParam("cursor", ""),
                get(path).queryParam("modelVersionId", data.model().toString()).queryParam("limit", "51"),
                get(path).queryParam("modelVersionId", data.model().toString()).queryParam("limit", "1", "2"))) {
            consoleCatalogError(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        }
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.actorId());
        consoleCatalogError(mvc.perform(get(path).queryParam("modelVersionId", data.model().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 401, null);
    }

    /** Console严格历史使用协作者项目真实RLS，归档可读且无需App设备绑定。 */
    @Test void consoleStrictHistoryUsesRealScopeAndClosedQuery() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'历史协作者')", otherTenant);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=?", f.appUserId());
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        String token = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), otherTenant, f.projectId())).value();
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_double)
                SELECT project_id,?,'temperature',(date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' - interval '1 day' + interval '11 hours 30 minutes'),gen_random_uuid(),'NUMBER',id,version_number,12.5
                  FROM dev_thing_model_version WHERE id=?
                """, data.first(), data.model());
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double)"
                + " VALUES (?,?,'temperature',(date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' - interval '1 day' + interval '12 hours'),gen_random_uuid(),99)", f.projectId(), data.first());
        MvcResult response = mvc.perform(consoleHistory(data.first(), data.model())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        JsonNode result = success(response);
        assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("requestedGranularity", "actualGranularity", "aggregation", "points");
        assertThat(result.path("points")).hasSize(1);
        JsonNode point = result.path("points").get(0);
        assertThat(point.path("value").asDouble()).isEqualTo(12.5);
        assertThat(point.path("thingModelVersionId").asString()).isEqualTo(data.model().toString());
        assertThat(point.path("ts").asString()).isEqualTo(historyDay().plusSeconds(11*3600+1800).toString());
        assertThat(point.path("sampleCount").asLong()).isEqualTo(1);
        for (MockHttpServletRequestBuilder call : List.of(
                consoleHistory(data.first(), data.model()).queryParam("unknown", "x"),
                consoleHistory(data.first(), data.model()).queryParam("aggregation", "MAX", "AVG"),
                consoleHistory(data.first(), data.model()).queryParam("granularity", ""),
                consoleHistory(data.first(), UUID.randomUUID()))) {
            consoleCatalogError(mvc.perform(call.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        }
        consoleCatalogError(mvc.perform(consoleHistory(UUID.randomUUID(), data.model())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 404, 30020);
        UUID unversionedType = UUID.randomUUID();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind)"
                + " VALUES (?,?,?,?,'未发布历史模型','STANDARD','DIRECT')", unversionedType, f.tenantId(), f.projectId(),
                "type_" + unversionedType.toString().replace("-", ""));
        UUID unbound = WebAppDataRuntimeFixture.addDevice(owner, f, unversionedType, null, false, Instant.now());
        consoleCatalogError(mvc.perform(consoleHistory(unbound, data.model())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 400, 10001);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.actorId());
        consoleCatalogError(mvc.perform(consoleHistory(data.first(), data.model())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 401, null);
    }

    /** AppBearer不能借新的Console严格历史入口跨身份读取。 */
    @Test void consoleStrictHistoryRejectsAppIdentity() throws Exception {
        String token = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        consoleCatalogError(mvc.perform(consoleHistory(data.first(), data.model())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 401, null);
    }

    /** @param device 精确设备 @param model 精确模型 @return Console历史固定一小时只读请求 */
    private MockHttpServletRequestBuilder consoleHistory(UUID device, UUID model) {
        return get("/api/v1/projects/" + f.projectId() + "/devices/" + device + "/telemetry/property/history/versioned")
                .queryParam("propertyKey", "temperature").queryParam("expectedModelVersionId", model.toString())
                .queryParam("from", historyDay().plusSeconds(11*3600).toString()).queryParam("to", historyDay().plusSeconds(12*3600).toString());
    }

    /** Console GET错误沿Spring原链：401可为空challenge，业务400保留错误码及no-store语义。 */
    private static void consoleCatalogError(MvcResult result, int status, Integer code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        if (code != null) assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isEqualTo(code);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    /** 同次请求四个完整运行header只由调用者选择版本，不承载tenant/project等授权身份。 */
    private MvcResult request(MockHttpServletRequestBuilder call) throws Exception {
        String token = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        return mvc.perform(call.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Application-Key", f.appKey()).header("X-Application-Version", f.applicationVersionId().toString())
                .header("X-Application-Revision", "2").header("X-Dashboard-Version", f.authorized().dashboardVersionId().toString())).andReturn();
    }

    /** 目录模型来自授权Schema，翻页仅追加有界cursor/limit。 */
    private MockHttpServletRequestBuilder catalog() {
        return get("/api/v1/app/devices/catalog").queryParam("modelVersionId", data.model().toString());
    }

    /** 历史固定一小时UTC窗口，与声明TIME_RANGE预设相等。 */
    private MockHttpServletRequestBuilder history() { return history("AVG"); }

    /** 单值不同聚合用于证明计划闭集拒绝，不以重复query语法失败代替业务反例。 */
    private MockHttpServletRequestBuilder history(String aggregation) {
        return get("/api/v1/app/devices/" + data.first() + "/properties/temperature/history/versioned")
                .queryParam("from", historyDay().plusSeconds(11*3600).toString()).queryParam("to", historyDay().plusSeconds(12*3600).toString())
                .queryParam("granularity", "RAW").queryParam("aggregation", aggregation)
                .queryParam("expectedModelVersionId", data.model().toString());
    }

    /** 快照模型身份必须使用完整不可变摘要。 */
    private String snapshotBody(UUID device) {
        return "{\"models\":[{\"versionId\":\"" + data.model() + "\",\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\","
                + "\"digest\":\"" + data.digest() + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}],"
                + currentBody(device).substring(1);
    }

    /** 稀疏属性请求没有客户端授权范围或Schema正文。 */
    private String currentBody(UUID device) {
        return "{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\"" + data.model()
                + "\",\"propertyKeys\":[\"temperature\"]}]}";
    }

    /** 告警过滤必须与同一个Schema绑定保持一致。 */
    private String alarmBody(UUID device) {
        return "{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\"" + data.model()
                + "\"}],\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"WARNING\"]}";
    }

    /** 成功与原始字节均由真实Jackson解析器验证。 */
    private static JsonNode success(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        noStore(result);
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
    /** 错误状态与业务码必须同时一致，不把系统故障当业务隐藏。 */
    private static void error(MvcResult result, int status, int code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isEqualTo(code);
        noStore(result);
    }
    /** 在安全链前置的no-store覆盖全部路径结果，且没有可驱动304的ETag。 */
    private static void noStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(304);
    }
    /** @return 专库的JDBC身份，在Spring初始化之前唯一建立 */
    private static String startDatabase() { RUNTIME_POSTGRES.start(); return RUNTIME_POSTGRES.getJdbcUrl(); }
    /** 本类独占迁移和连接配置，不改变生产的数据库或调度默认值。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 单一专库贯穿Flyway和普通APP业务数据源。 */
        @Bean DynamicPropertyRegistrar runtimeDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.flyway.user", RUNTIME_POSTGRES::getUsername);
                registry.add("spring.flyway.password", RUNTIME_POSTGRES::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }
    /** 当前UTC前一日避免固定历史日期越过FREE窗口。 */
    private static Instant historyDay() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusSeconds(86400); }

    /** WebApp不会把套餐投影缺失转换为空数据或误报设备不存在。 */
    @Test void missingHistoryPlanPreserves503Contract() throws Exception {
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",f.tenantId());
        error(request(history()),503,50048);
    }

}
