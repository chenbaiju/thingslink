package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.enduser.application.AppUserDashboardGrantManagementService;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** S12-2a3b：真实Console过滤链、普通APP数据库事务与公共幂等墓碑验证管理闭环。 */
@AutoConfigureMockMvc
@Import(AppUserDashboardGrantApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"GRANT_POSTGRES"})
class AppUserDashboardGrantApiTests extends AbstractIntegrationTest {
    /** 独占数据库允许精确注入审计约束故障，不干扰共享Bootstrap夹具。 */
    private static final PostgreSQLContainer<?> GRANT_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_grant_api").withUsername("thingslink").withPassword("thingslink");
    /** 在Spring迁移与应用池创建之前启动同一专库，生命周期随测试JVM回收。 */
    private static final String DATABASE_URL = startDatabase();
    /** 响应根对象与精确字段检查，不用DTO反序列化隐藏额外字段。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 固定外部响应闭集，不包含内部grant身份、tenant/project或Console操作者。 */
    private static final Set<String> RESPONSE_FIELDS = Set.of("appUserId", "dashboardId", "permission", "status",
            "revision", "createdAt", "updatedAt", "revokedAt");
    /** 真实Spring MVC与完整Console安全过滤链。 */
    @Autowired private MockMvc mvc;
    /** 仅省略登录交互，仍经生产签名器、验签及真实成员状态。 */
    @Autowired private TokenIssuer tokens;
    /** 独立证明通过公共前置后仍由真实管理事务执行生命周期拒绝。 */
    @Autowired private AppUserDashboardGrantManagementService management;
    /** 普通运行连接用于校验没有使用owner执行HTTP。 */
    @Autowired private JdbcTemplate application;
    /** 只在幂等在途用例围绕真实审计插入设屏障，不替换数据库返回值。 */
    @MockitoSpyBean private AuditLogService audit;
    /** 基类runner固定指向共享库，本类改由下方专库runner放宽测试写速率。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedSharedQuotaRunner;
    /** Owner仅生成父事实、读取提交结果与注入临时数据库故障。 */
    private JdbcTemplate owner;

    /** 每例独立父身份；独占容器回收所有本类事实，不做共享库全表清理。 */
    @BeforeEach
    void setup() {
        owner = ownerJdbc();
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_grant_api");
    }

    /** OWNER/ADMIN均可完成授予、零写、撤销和重授；OBSERVER App角色不自动扩大设备权限。 */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    void managersCompleteGrantLifecycleWithExactResponseAndAudit(ProjectRole role) throws Exception {
        Fixture f = fixture(role);
        assertThat(ok(request(f, get(base(f)))).path("items")).isEmpty();
        JsonNode first = ok(update(f, "0", "ACTIVE"));
        assertGrant(first, f, "ACTIVE", "1");
        assertThat(ok(request(f, get(item(f))))).isEqualTo(first);
        assertThat(ok(update(f, "1", "ACTIVE"))).isEqualTo(first);
        assertThat(audits(f)).isEqualTo(1);
        JsonNode revoked = ok(update(f, "1", "REVOKED"));
        assertGrant(revoked, f, "REVOKED", "2");
        assertThat(revoked.path("revokedAt")).isEqualTo(revoked.path("updatedAt"));
        JsonNode regranted = ok(update(f, "2", "ACTIVE"));
        assertGrant(regranted, f, "ACTIVE", "3");
        assertThat(regranted.path("createdAt")).isEqualTo(first.path("createdAt"));
        assertThat(audits(f)).isEqualTo(3);
        List<String> actions = owner.queryForList("SELECT action FROM sys_audit_log WHERE project_id=? ORDER BY id",
                String.class, f.projectId());
        assertThat(actions).containsExactly("enduser.dashboard.granted", "enduser.dashboard.revoked", "enduser.dashboard.granted");
        for (String details : owner.queryForList("SELECT details::text FROM sys_audit_log WHERE project_id=?",
                String.class, f.projectId())) {
            assertThat(fields(JSON.readTree(details))).containsExactlyInAnyOrder("appUserId", "dashboardId", "status", "revision");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM app_user_device WHERE project_id=?", Long.class, f.projectId())).isZero();
    }

    /** manage只属于OWNER/ADMIN；不能因同属enduser路径把既有OPERATOR权限借来授予grant。 */
    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    void ordinaryMembersCannotReadOrWriteGrantManagement(ProjectRole role) throws Exception {
        Fixture f = fixture(role);
        error(request(f, get(base(f))), 403, 60024);
        error(request(f, get(item(f))), 403, 60024);
        error(update(f, "0", "ACTIVE"), 403, 60024);
        assertThat(grants(f)).isZero();
        assertThat(audits(f)).isZero();
    }

    /** 缺认证在安全链拒绝；别的项目成员无法通过路径枚举本项目授权。 */
    @Test
    void rejectsUnauthenticatedAndForeignProjectAccess() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        Fixture outsider = fixture(ProjectRole.OWNER);
        for (MockHttpServletRequestBuilder call : List.of(get(base(f)), get(item(f)),
                put(item(f)).contentType(MediaType.APPLICATION_JSON).content(envelope("0", "ACTIVE")))) {
            assertThat(mvc.perform(call).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        error(request(outsider, get(base(f))), 404, 50001);
        error(request(outsider, put(item(f)).contentType(MediaType.APPLICATION_JSON).content(envelope("0", "ACTIVE"))), 404, 50001);
    }

    /** 首次撤销不造历史；不存在与跨域用户、看板使用相同隐藏分类。 */
    @Test
    void hidesMissingAndForeignTargets() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        Fixture other = fixture(ProjectRole.OWNER);
        error(request(f, get(item(f))), 404, 60025);
        error(update(f, "0", "REVOKED"), 404, 60025);
        for (UUID userId : List.of(Uuid7.generate(), other.userId())) {
            error(request(f, put(base(f.projectId(), userId) + "/" + f.dashboardId())
                    .contentType(MediaType.APPLICATION_JSON).content(envelope("0", "ACTIVE"))), 404, 60025);
        }
        for (UUID dashboardId : List.of(Uuid7.generate(), other.dashboardId())) {
            error(request(f, put(base(f) + "/" + dashboardId).contentType(MediaType.APPLICATION_JSON)
                    .content(envelope("0", "ACTIVE"))), 404, 60025);
        }
        assertThat(grants(f)).isZero();
        assertThat(audits(f)).isZero();
    }

    /** 架构§8.1.1：已有套餐快照的归档写可先429；真实业务事务另按50017拒绝。 */
    @Test
    void archivedProjectIsReadOnly() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        JsonNode original = ok(update(f, "0", "ACTIVE"));
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        assertThat(ok(request(f, get(item(f))))).isEqualTo(original);
        assertThat(ok(request(f, get(base(f)))).path("items")).hasSize(1);
        error(update(f, "1", "REVOKED"), 429, 10029);
        TenantContext.set(new TenantScope(f.tenantId(), f.projectId(), f.accountId()));
        try {
            assertThatThrownBy(() -> management.update(f.projectId(), f.userId(), f.dashboardId(), "1", "REVOKED"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_READ_ONLY));
        } finally {
            TenantContext.clear();
        }
        assertThat(ok(request(f, get(item(f))))).isEqualTo(original);
        assertThat(audits(f)).isEqualTo(1);
    }

    /** 已软删看板与暂停用户角色的历史授权仍可读取，但不可继续执行grant管理写。 */
    @Test
    void historicalGrantDoesNotLeakDeletedDashboardFields() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        JsonNode original = ok(update(f, "0", "ACTIVE"));
        owner.update("UPDATE dash_dashboard SET deleted_at=now(),publication_revision=1 WHERE id=?", f.dashboardId());
        owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?", f.userId());
        owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", f.userId());
        assertThat(ok(request(f, get(item(f))))).isEqualTo(original);
        assertThat(ok(request(f, get(base(f)))).path("items").get(0)).isEqualTo(original);
        error(update(f, "1", "REVOKED"), 404, 60025);
        assertThat(audits(f)).isEqualTo(1);
    }

    /** 不支持的状态和非规范Long字符串属于业务语法，而不是JSON信封错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "01", "-1", "1.0", " 0", "9223372036854775808"})
    void rejectsInvalidRevisionBusinessSyntax(String revision) throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        error(update(f, revision, "ACTIVE"), 400, 60026);
        assertThat(grants(f)).isZero();
    }

    /** 错误状态仍是字符串，不能被外层解析器误分类为10002或无条件转换枚举500。 */
    @Test
    void rejectsInvalidStatusBusinessSyntax() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        error(update(f, "0", "active"), 400, 60026);
        assertThat(grants(f)).isZero();
    }

    /** HTTP必须保留原始重复键/类型/缺失事实，不能先绑定DTO丢失信封信息。 */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "{\"expectedRevision\":0,\"status\":\"ACTIVE\"}",
            "{\"expectedRevision\":\"0\",\"status\":null}",
            "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\",\"status\":\"ACTIVE\"}",
            "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\",\"extra\":1}"})
    void rejectsMalformedEnvelopesBeforeWriting(String body) throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        error(request(f, put(item(f)).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10002);
        assertThat(grants(f)).isZero();
        assertThat(audits(f)).isZero();
    }

    /** CAS旧revision与Long耗尽都409；匹配同状态即使在Long上限仍完全零写。 */
    @Test
    void staleAndExhaustedRevisionsDoNotOverwriteGrant() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        ok(update(f, "0", "ACTIVE"));
        error(update(f, "0", "REVOKED"), 409, 60027);
        owner.update("UPDATE app_user_dashboard SET revision=? WHERE project_id=?", Long.MAX_VALUE, f.projectId());
        JsonNode original = ok(request(f, get(item(f))));
        assertThat(original.path("revision").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(ok(update(f, Long.toString(Long.MAX_VALUE), "ACTIVE"))).isEqualTo(original);
        error(update(f, Long.toString(Long.MAX_VALUE), "REVOKED"), 409, 60027);
        assertThat(ok(request(f, get(item(f))))).isEqualTo(original);
        assertThat(audits(f)).isEqualTo(1);
    }

    /** 不透明游标按稳定看板ID推进，ACTIVE/REVOKED都保留且外部行字段始终闭合。 */
    @Test
    void listsBothStatusesWithBoundedCursorPages() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        ok(update(f, "0", "ACTIVE"));
        Fixture second = new Fixture(f.tenantId(), f.projectId(), f.accountId(), f.userId(), Uuid7.generate());
        insertDashboard(second);
        ok(update(second, "0", "ACTIVE"));
        ok(update(second, "1", "REVOKED"));
        JsonNode page = ok(request(f, get(base(f)).param("limit", "1")));
        assertThat(fields(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("items")).hasSize(1);
        JsonNode next = ok(request(f, get(base(f)).param("limit", "1").param("cursor", page.path("nextCursor").asString())));
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(next.path("nextCursor").isNull()).isTrue();
        assertThat(next.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("dashboardId")).isNotEqualTo(next.path("items").get(0).path("dashboardId"));
        assertThat(fields(next.path("items").get(0))).isEqualTo(RESPONSE_FIELDS);
        error(request(f, get(base(f)).param("limit", "0")), 400, 10001);
        error(request(f, get(base(f)).param("limit", "201")), 400, 10001);
        error(request(f, get(base(f)).param("cursor", "not-a-cursor")), 400, 10001);
    }

    /** 真实审计INSERT约束失败回滚grant；不能把授权提交与安全审计分成两笔。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void auditSqlFailureRollsBackCreationOrRevocation(boolean creating) throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        if (!creating) ok(update(f, "0", "ACTIVE"));
        owner.execute("""
                ALTER TABLE sys_audit_log ADD CONSTRAINT sys_audit_log_grant_test_failure_ck
                CHECK (action NOT LIKE 'enduser.dashboard.%') NOT VALID
                """);
        try {
            MvcResult result = update(f, creating ? "0" : "1", creating ? "ACTIVE" : "REVOKED");
            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            assertThat(grants(f)).isEqualTo(creating ? 0 : 1);
            assertThat(audits(f)).isEqualTo(creating ? 0 : 1);
            if (!creating) assertGrant(ok(request(f, get(item(f)))), f, "ACTIVE", "1");
        } finally {
            owner.execute("ALTER TABLE sys_audit_log DROP CONSTRAINT sys_audit_log_grant_test_failure_ck");
        }
        assertThat(ok(update(f, creating ? "0" : "1", creating ? "ACTIVE" : "REVOKED"))).isNotNull();
    }

    /** 公共完成墓碑只返回10014，调用者通过详情刷新；异原文仍10009且不重复审计。 */
    @Test
    void completedIdempotencyUsesTombstoneAndGetRefresh() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        String key = UUID.randomUUID().toString();
        JsonNode original = ok(keyed(f, key, envelope("0", "ACTIVE")));
        JsonNode replay = error(keyed(f, key, envelope("0", "ACTIVE")), 409, 10014);
        assertThat(replay.has("appUserId")).isFalse();
        error(keyed(f, key, envelope("1", "REVOKED")), 409, 10009);
        assertThat(ok(request(f, get(item(f))))).isEqualTo(original);
        assertThat(audits(f)).isEqualTo(1);
    }

    /** 第一请求在真实事务审计前阻塞时，第二请求由实际公共幂等存储拒绝为10010。 */
    @Test
    void inFlightRequestIsRejectedWithoutEnteringSecondMutation() throws Exception {
        Fixture f = fixture(ProjectRole.OWNER);
        String key = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            AuditLogEntry entry = invocation.getArgument(0);
            if (f.projectId().equals(entry.projectId()) && entry.action().equals("enduser.dashboard.granted")) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(audit).record(any(AuditLogEntry.class));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> keyed(f, key, envelope("0", "ACTIVE")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            error(keyed(f, key, envelope("0", "ACTIVE")), 409, 10010);
            release.countDown();
            ok(first.get(10, TimeUnit.SECONDS));
            assertThat(audits(f)).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }

    /** 三个真实父域和项目角色由owner备夹具；请求仍走普通运行身份。 */
    private Fixture fixture(ProjectRole role) {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'授权HTTP租户')", f.tenantId());
        insertAccount(f.tenantId(), f.accountId());
        UUID ownerId = role == ProjectRole.OWNER ? f.accountId() : Uuid7.generate();
        if (!ownerId.equals(f.accountId())) insertAccount(f.tenantId(), ownerId);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'授权HTTP项目',?)",
                f.projectId(), f.tenantId(), "grant_" + f.projectId().toString().replace("-", ""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), f.projectId(), ownerId);
        if (!ownerId.equals(f.accountId())) owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                Uuid7.generate(), f.projectId(), f.accountId(), role.name());
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'test-only-unusable-hash')",
                f.userId(), f.tenantId(), f.userId().toString());
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",
                Uuid7.generate(), f.tenantId(), f.projectId(), f.userId());
        insertDashboard(f);
        return f;
    }

    /** 没有草稿或current版本的目录是预授予合法目标，不以伪发布绕过生产资格。 */
    private void insertDashboard(Fixture f) {
        owner.update("INSERT INTO dash_dashboard(id,tenant_id,project_id,management_name,created_by,updated_by) VALUES (?,?,?,'不应泄露的目录名',?,?)",
                f.dashboardId(), f.tenantId(), f.projectId(), f.accountId(), f.accountId());
    }

    /** 使用真实Console账号和租户成员满足JWT生命周期检查。 */
    private void insertAccount(UUID tenant, UUID account) {
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'test-only-unusable-hash','授权管理者',now())",
                account, account + "@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), tenant, account);
    }

    /** 对提交结果只按当前项目读取，避免别的用例审计混入计数。 */
    private long audits(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'enduser.dashboard.%'",
                Long.class, f.projectId());
    }

    /** 授权行数用于证明失败和重放都无额外副作用。 */
    private long grants(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM app_user_dashboard WHERE project_id=?", Long.class, f.projectId());
    }

    /** 成员身份由生产JWT传递；不通过withMockUser绕过范围与权限复核。 */
    private MvcResult request(Fixture f, MockHttpServletRequestBuilder call) throws Exception {
        String bearer = tokens.issue(new AuthenticatedPrincipal(f.accountId(), f.tenantId(), f.projectId())).value();
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)).andReturn();
    }

    /** 保留精确请求原文参与公共幂等身份。 */
    private MvcResult keyed(Fixture f, String key, String body) throws Exception {
        return request(f, put(item(f)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 业务输入字符串交原解析器，不预先进行枚举/Long绑定。 */
    private MvcResult update(Fixture f, String revision, String status) throws Exception {
        return request(f, put(item(f)).contentType(MediaType.APPLICATION_JSON).content(envelope(revision, status)));
    }

    /** 两个字段都是字符串；业务语法由服务负责。 */
    private static String envelope(String revision, String status) {
        return JSON.createObjectNode().put("expectedRevision", revision).put("status", status).toString();
    }

    /** @return 当前项目用户的管理集合路径 */
    private static String base(Fixture f) { return base(f.projectId(), f.userId()); }

    /** @return 规范UUID路径，不引入客户端tenant轴 */
    private static String base(UUID project, UUID user) {
        return "/api/v1/projects/" + project + "/end-users/" + user + "/dashboard-grants";
    }

    /** @return 一个稳定看板的管理事实路径 */
    private static String item(Fixture f) { return base(f) + "/" + f.dashboardId(); }

    /** 外部投影字段和Long类型完全按冻结闭集检查。 */
    private static void assertGrant(JsonNode node, Fixture f, String status, String revision) {
        assertThat(fields(node)).isEqualTo(RESPONSE_FIELDS);
        assertThat(node.path("appUserId").asString()).isEqualTo(f.userId().toString());
        assertThat(node.path("dashboardId").asString()).isEqualTo(f.dashboardId().toString());
        assertThat(node.path("permission").asString()).isEqualTo("READ");
        assertThat(node.path("status").asString()).isEqualTo(status);
        assertThat(node.path("revision").isString()).isTrue();
        assertThat(node.path("revision").asString()).isEqualTo(revision);
        assertThat(node.path("revokedAt").isNull()).isEqualTo(status.equals("ACTIVE"));
    }

    /** 不用忽略未知字段的DTO映射替代闭集断言。 */
    private static Set<String> fields(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 仅200可作为成功，不以任意2xx掩盖合同偏差。 */
    private static JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** HTTP与业务码同时核对，不把基础设施异常当成业务拒绝。 */
    private static JsonNode error(MvcResult result, int status, int code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(code);
        return body;
    }

    /** Owner只连本类专库，不能使用基类固定共享库的fixtureOwnerConnection。 */
    private static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL, GRANT_POSTGRES.getUsername(), GRANT_POSTGRES.getPassword()));
    }

    /** 保持至测试JVM结束，不逐例停库破坏Spring缓存池。 */
    private static String startDatabase() { GRANT_POSTGRES.start(); return GRANT_POSTGRES.getJdbcUrl(); }

    /** 专库隔离受控审计故障；禁用本片无关的异步外发，真实HTTP服务保持装配。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 所有生产连接池与Flyway绑定同一实例。 */
        @Bean DynamicPropertyRegistrar grantDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.flyway.user", GRANT_POSTGRES::getUsername);
                registry.add("spring.flyway.password", GRANT_POSTGRES::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }

        /** 仅专库功能测试放宽写速率，避免连续夹具操作误测生产限流预算。 */
        @Bean ApplicationRunner grantTestQuota() {
            return args -> ownerJdbc().update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");
        }
    }

    /** @param tenantId 项目租户 @param projectId 项目 @param accountId Console账号 @param userId App用户 @param dashboardId 稳定看板 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID dashboardId) { }
}
