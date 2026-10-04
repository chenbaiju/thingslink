package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.application.publication.DashboardDataAdapterQualificationPort;
import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.infrastructure.qualification.ManagedWebAppHostQualificationAdapter;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 看板目录、详情、草稿与不可变版本历史HTTP边界的真实认证、项目隔离和响应合同验收。
 *
 * <p>S12-1d3接入基础读取，S12-1d4增加领域幂等创建、已有看板改名与草稿保存，S12-1e1a至1e1e增加
 * 不可变版本历史读取、发布、回滚、撤回与软删除。本类使用生产JWT、安全过滤链和真实PostgreSQL，证明角色、项目、
 * 严格原文与领域创建/公共发布生命周期幂等边界均落到真实调用链。</p>
 */
@AutoConfigureMockMvc
@DisplayName("看板管理接口")
class DashboardManagementApiTests extends AbstractIntegrationTest {

    /** JSON只用于读取HTTP响应，数据库仍负责解析真实jsonb夹具。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 超过JavaScript安全整数的合法revision，证明HTTP不会退化成number。 */
    private static final long LARGE_REVISION = 9_007_199_254_740_993L;

    /** 当前用例创建的项目；用例结束后按项目精确回收看板事实。 */
    private final List<UUID> fixtureProjectIds = new ArrayList<>();

    /** 真实Spring MVC与完整安全过滤链入口。 */
    @Autowired
    private MockMvc mvc;

    /** 生产JWT签名器；测试仅跳过登录交互，不绕过验签和项目范围。 */
    @Autowired
    private TokenIssuer tokens;

    /** 正向HTTP发布替换宿主资格事实；保留适配器的全部端口类型，不替换发布事务或数据库。 */
    @MockitoBean(enforceOverride = true)
    private ManagedWebAppHostQualificationAdapter hostQualifications;

    /** 空组件画布没有数据适配需求；替身使测试在未来误加需求时保持明确结果。 */
    @MockitoBean(enforceOverride = true)
    private DashboardDataAdapterQualificationPort dataAdapters;

    /** 每例恢复受控正向资格，生产缺失资格的60041由专门反例覆盖。 */
    @BeforeEach
    void qualifyPublishedDashboardForHttpTests() {
        when(hostQualifications.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));
        when(dataAdapters.supports(any(), any())).thenReturn(true);
        when(dataAdapters.supportsHistory(any(), any())).thenReturn(true);
    }

    /** 回收当前用例提交的看板事实，避免共享数据库的后续全项目重置被新外键阻断。 */
    @AfterEach
    void clearPersistedDashboardFixtures() throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            DashboardApplicationFixtureCleaner.clearProjects(connection, fixtureProjectIds);
        } finally {
            fixtureProjectIds.clear();
        }
    }

    /** 未认证读取必须在进入Controller前由Console安全链拒绝。 */
    @Test
    @DisplayName("未认证看板读取统一返回401")
    void rejectsUnauthenticatedReads() throws Exception {
        UUID projectId = Uuid7.generate();
        UUID dashboardId = Uuid7.generate();
        UUID versionId = Uuid7.generate();

        for (String path : List.of(
                basePath(projectId), itemPath(projectId, dashboardId), draftPath(projectId, dashboardId),
                versionsPath(projectId, dashboardId), versionPath(projectId, dashboardId, versionId))) {
            MvcResult response = mvc.perform(get(path)).andReturn();
            assertThat(response.getResponse().getStatus()).as(path).isEqualTo(401);
            assertThat(response.getHandler()).as(path).isNull();
        }
    }

    /** S14-R4c：创建HTTP的额度拒绝可解释，同键重放不因额度已满失败。 */
    @Test
    void creationCapacityErrorsAndReplayStayStableAtHttpBoundary() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", fixture.tenantId());
        }
        String body = createEnvelope("额度看板", validContent("额度"));
        created(write(fixture, post(basePath(fixture.projectId())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r4c-first").content(body)));
        created(write(fixture, post(basePath(fixture.projectId())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r4c-first").content(body)));
        error(write(fixture, post(basePath(fixture.projectId())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r4c-second").content(body)), 409, 60059);
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", fixture.tenantId());
        }
        error(write(fixture, post(basePath(fixture.projectId())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r4c-third").content(body)), 503, 50048);
    }

    /** 四种Console角色均可读取同一看板聚合与历史，且内部归属和模型关系不进入HTTP。 */
    @ParameterizedTest(name = "{0}可读取看板目录、草稿和版本")
    @EnumSource(ProjectRole.class)
    void everyProjectRoleReadsCatalogItemAndDraft(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        JsonNode emptyPage = ok(authenticatedGet(fixture, basePath(fixture.projectId())));
        assertThat(emptyPage.path("items")).isEmpty();
        assertThat(emptyPage.path("nextCursor").isNull()).isTrue();
        assertThat(emptyPage.path("hasMore").asBoolean()).isFalse();

        Instant updatedAt = Instant.parse("2026-09-06T22:10:11.123456Z");
        JsonNode content = JSON.readTree("""
                {"schemaVersion":"tc.dashboard/v1","title":"冷链总览",\
                 "pages":[{"id":"main","title":"首页"}],"models":[]}""");
        UUID dashboardId = insertDashboard(
                fixture, "冷链总览", LARGE_REVISION, LARGE_REVISION, updatedAt, content, false);
        JsonNode emptyVersions = ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), dashboardId)));
        assertThat(emptyVersions.path("items")).isEmpty();
        assertThat(emptyVersions.path("nextCursor").isNull()).isTrue();
        assertThat(emptyVersions.path("hasMore").asBoolean()).isFalse();
        UUID versionId = insertDashboardVersion(
                fixture, dashboardId, LARGE_REVISION, LARGE_REVISION, updatedAt, content);

        JsonNode page = ok(authenticatedGet(fixture, basePath(fixture.projectId())));
        assertThat(fields(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertCatalog(page.path("items").get(0), dashboardId, "冷链总览", LARGE_REVISION);

        JsonNode item = ok(authenticatedGet(fixture, itemPath(fixture.projectId(), dashboardId)));
        assertCatalog(item, dashboardId, "冷链总览", LARGE_REVISION);

        JsonNode draft = ok(authenticatedGet(fixture, draftPath(fixture.projectId(), dashboardId)));
        assertThat(fields(draft)).containsExactlyInAnyOrder("dashboardId", "content", "revision", "updatedAt");
        assertThat(draft.path("dashboardId").asString()).isEqualTo(dashboardId.toString());
        assertThat(draft.path("revision").isString()).isTrue();
        assertThat(draft.path("revision").asString()).isEqualTo(Long.toString(LARGE_REVISION));
        assertThat(draft.path("content")).isEqualTo(content);
        assertThat(draft.path("updatedAt").asString()).isEqualTo(updatedAt.toString());
        assertThat(draft.has("tenantId")).isFalse();
        assertThat(draft.has("projectId")).isFalse();
        assertThat(draft.has("updatedBy")).isFalse();
        assertThat(draft.has("modelReferences")).isFalse();

        JsonNode versions = ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), dashboardId)));
        assertThat(versions.path("items")).hasSize(1);
        assertVersionSummary(versions.path("items").get(0),
                versionId, LARGE_REVISION, LARGE_REVISION, updatedAt);
        assertVersionDetail(ok(authenticatedGet(
                fixture, versionPath(fixture.projectId(), dashboardId, versionId))),
                versionId, LARGE_REVISION, LARGE_REVISION, updatedAt, content);
    }

    /** 项目归档保留成员只读能力，不能把只读GET误接到ACTIVE写许可。 */
    @Test
    @DisplayName("归档项目仍可读取已有看板")
    void archivedProjectKeepsDashboardReads() throws Exception {
        Fixture fixture = seed(ProjectRole.VIEWER);
        UUID dashboardId = insertDashboard(
                fixture, "归档看板", 0, 0, Instant.now(), draftContent("归档看板"), false);
        Instant publishedAt = Instant.parse("2026-09-06T22:20:11.123456Z");
        JsonNode schema = JSON.readTree(validContent("归档历史"));
        UUID versionId = insertDashboardVersion(fixture, dashboardId, 1, 0, publishedAt, schema);
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        assertCatalog(ok(authenticatedGet(fixture, itemPath(fixture.projectId(), dashboardId))),
                dashboardId, "归档看板", 0);
        assertThat(ok(authenticatedGet(fixture, draftPath(fixture.projectId(), dashboardId)))
                .path("dashboardId").asString()).isEqualTo(dashboardId.toString());
        assertVersionSummary(ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), dashboardId)))
                .path("items").get(0), versionId, 1, 0, publishedAt);
        assertVersionDetail(ok(authenticatedGet(
                fixture, versionPath(fixture.projectId(), dashboardId, versionId))),
                versionId, 1, 0, publishedAt, schema);
    }

    /** 非成员、跨项目与软删除目标必须按项目或看板边界折叠，不能泄露内部存在性。 */
    @Test
    @DisplayName("非成员和不可见看板保持稳定隐藏边界")
    void hidesNonMemberCrossProjectAndDeletedDashboards() throws Exception {
        Fixture caller = seed(ProjectRole.OWNER);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID localDeleted = insertDashboard(
                caller, "已删除看板", 0, 0, Instant.now(), draftContent("已删除看板"), true);
        UUID foreignDashboard = insertDashboard(
                foreign, "其他项目看板", 0, 0, Instant.now(), draftContent("其他项目看板"), false);
        UUID localDashboard = insertDashboard(
                caller, "本地可见看板", 0, 0, Instant.now(), draftContent("本地可见看板"), false);
        UUID localDeletedVersion = insertDashboardVersion(
                caller, localDeleted, 1, 0, Instant.now(), JSON.readTree(validContent("已删历史")));
        UUID foreignVersion = insertDashboardVersion(
                foreign, foreignDashboard, 1, 0, Instant.now(), JSON.readTree(validContent("其他历史")));

        error(authenticatedGet(caller, basePath(foreign.projectId())), 404, 50001);
        error(authenticatedGet(caller, versionsPath(foreign.projectId(), foreignDashboard)), 404, 50001);
        error(authenticatedGet(caller, itemPath(caller.projectId(), foreignDashboard)), 404, 60034);
        error(authenticatedGet(caller, itemPath(caller.projectId(), localDeleted)), 404, 60034);
        error(authenticatedGet(caller, draftPath(caller.projectId(), localDeleted)), 404, 60034);
        error(authenticatedGet(caller, versionsPath(caller.projectId(), localDeleted)), 404, 60034);
        error(authenticatedGet(
                caller, versionPath(caller.projectId(), localDeleted, localDeletedVersion)), 404, 60034);
        error(authenticatedGet(
                caller, versionPath(caller.projectId(), localDashboard, foreignVersion)), 404, 60047);
        error(authenticatedGet(
                caller, versionPath(caller.projectId(), localDashboard, Uuid7.generate())), 404, 60047);
        error(authenticatedGet(caller, itemPath(caller.projectId(), Uuid7.generate())), 404, 60034);
    }

    /** 看板目录沿稳定游标分页，非法limit、游标和UUID均使用公共参数错误。 */
    @Test
    @DisplayName("看板分页和路径参数边界稳定")
    void validatesPaginationCursorAndIdentifiers() throws Exception {
        Fixture fixture = seed(ProjectRole.OPERATOR);
        Instant older = Instant.parse("2026-09-06T21:00:00.123456Z");
        Instant newer = older.plusSeconds(1);
        UUID firstAtSameTime = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondAtSameTime = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertDashboard(fixture, firstAtSameTime, "同刻一", 0, 0, older, draftContent("同刻一"), false);
        insertDashboard(fixture, secondAtSameTime, "同刻二", 0, 0, older, draftContent("同刻二"), false);
        UUID newest = insertDashboard(fixture, "最新", 0, 0, newer, draftContent("最新"), false);

        JsonNode first = ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "?limit=2"));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(newest.toString(), firstAtSameTime.toString());
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asString();

        JsonNode second = ok(authenticatedGet(
                fixture, basePath(fixture.projectId()) + "?limit=2&cursor=" + cursor));
        assertThat(second.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(secondAtSameTime.toString());
        assertThat(second.path("hasMore").asBoolean()).isFalse();

        for (String path : List.of(
                basePath(fixture.projectId()) + "?limit=0",
                basePath(fixture.projectId()) + "?limit=201",
                basePath(fixture.projectId()) + "?cursor=invalid",
                basePath(fixture.projectId()) + "/not-a-uuid",
                versionsPath(fixture.projectId(), newest) + "?limit=0",
                versionsPath(fixture.projectId(), newest) + "?limit=201",
                versionsPath(fixture.projectId(), newest) + "?cursor=invalid",
                basePath(fixture.projectId()) + "/not-a-uuid/versions",
                versionsPath(fixture.projectId(), newest) + "/not-a-uuid")) {
            error(authenticatedGet(fixture, path), 400, 10001);
        }
    }

    /** 版本历史使用单调版本号倒序游标，不受发布时间排序或JavaScript数值精度影响。 */
    @Test
    @DisplayName("看板版本历史按版本号倒序稳定分页")
    void versionHistoryUsesDescendingCursorAndLongStrings() throws Exception {
        Fixture fixture = seed(ProjectRole.OPERATOR);
        UUID dashboardId = insertDashboard(
                fixture, "版本分页", 0, 0, Instant.now(), draftContent("版本分页"), false);
        Instant at = Instant.parse("2026-09-06T23:00:00.123456Z");
        UUID oldest = insertDashboardVersion(
                fixture, dashboardId, 1, 0, at.plusSeconds(30), JSON.readTree(validContent("版本一")));
        UUID middle = insertDashboardVersion(
                fixture, dashboardId, 2, 1, at.plusSeconds(20), JSON.readTree(validContent("版本二")));
        UUID newest = insertDashboardVersion(
                fixture, dashboardId, LARGE_REVISION, LARGE_REVISION, at.plusSeconds(10),
                JSON.readTree(validContent("大版本")));

        JsonNode first = ok(authenticatedGet(
                fixture, versionsPath(fixture.projectId(), dashboardId) + "?limit=2"));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(newest.toString(), middle.toString());
        assertThat(first.path("items").get(0).path("versionNumber").isString()).isTrue();
        assertThat(first.path("items").get(0).path("versionNumber").asString())
                .isEqualTo(Long.toString(LARGE_REVISION));
        assertThat(first.path("items").get(0).path("sourceDraftRevision").asString())
                .isEqualTo(Long.toString(LARGE_REVISION));
        assertThat(first.path("hasMore").asBoolean()).isTrue();

        JsonNode last = ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), dashboardId)
                + "?limit=2&cursor=" + first.path("nextCursor").asString()));
        assertThat(last.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(oldest.toString());
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }

    /** OWNER与ADMIN可在无幂等键时直接发布，201正文和Location必须指向同一个新版本。 */
    @ParameterizedTest(name = "{0}可发布看板新版本")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色发布看板并返回精确版本位置")
    void managementRolesPublishDashboardVersion(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID dashboardId = createPublishableDashboard(fixture, "待发布看板", "发布版本");
        JsonNode content = ok(authenticatedGet(fixture, draftPath(fixture.projectId(), dashboardId)))
                .path("content");
        String path = versionsPath(fixture.projectId(), dashboardId);

        MvcResult result = write(fixture, post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0")));
        JsonNode version = created(result);
        UUID versionId = UUID.fromString(version.path("id").asString());

        assertPublishedVersion(version, versionId, 1, 0, content);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(versionPath(fixture.projectId(), dashboardId, versionId));
        assertThat(publicationState(dashboardId)).isEqualTo("1:" + versionId + ":1");
        assertThat(publicationAuditCount(dashboardId)).isOne();
    }

    /** 只读角色、归档项目和不可见看板分别保留角色、生命周期与资源隐藏错误。 */
    @Test
    @DisplayName("看板发布拒绝只读角色、归档项目和不可见目标")
    void publicationPreservesAuthorizationLifecycleAndVisibilityErrors() throws Exception {
        for (ProjectRole role : List.of(ProjectRole.OPERATOR, ProjectRole.VIEWER)) {
            Fixture fixture = seed(role);
            UUID dashboardId = insertDashboard(
                    fixture, "只读角色", 0, 0, Instant.now(), draftContent("只读角色"), false);
            error(write(fixture, post(versionsPath(fixture.projectId(), dashboardId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishEnvelope("0", "0"))), 403, 60035);
        }

        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedDashboard = insertDashboard(
                archived, "归档发布", 0, 0, Instant.now(), draftContent("归档发布"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(versionsPath(archived.projectId(), archivedDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 403, 50017);

        Fixture owner = seed(ProjectRole.OWNER);
        UUID deleted = insertDashboard(
                owner, "删除发布", 0, 0, Instant.now(), draftContent("删除发布"), true);
        for (UUID hidden : List.of(deleted, Uuid7.generate())) {
            error(write(owner, post(versionsPath(owner.projectId(), hidden))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishEnvelope("0", "0"))), 404, 60034);
        }
    }

    /** revision语法在严格外层解析之后由领域合同裁决，双轴任一陈旧都统一60040。 */
    @Test
    @DisplayName("看板发布区分非法revision与双轴陈旧")
    void publicationDistinguishesInvalidAndStaleRevisions() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = insertDashboard(
                fixture, "Revision边界", 4, 7, Instant.now(), draftContent("Revision边界"), false);
        String path = versionsPath(fixture.projectId(), dashboardId);

        for (String invalid : List.of("", " 7", "-1", "01", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publishEnvelope(invalid, "4"))), 400, 60039);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("6", "4"))), 409, 60040);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("7", "3"))), 409, 60040);
        assertThat(publicationState(dashboardId)).isEqualTo("4:null:0");
    }

    /** 宿主资格端口未返回当前描述符时继续503失败关闭，不能因HTTP可达而误报可发布。 */
    @Test
    @DisplayName("看板发布在宿主资格不可用时返回60041")
    void publicationFailsClosedWhenHostQualificationIsUnavailable() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = createPublishableDashboard(fixture, "资格缺失", "资格缺失");
        when(hostQualifications.current()).thenReturn(Optional.empty());

        error(write(fixture, post(versionsPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 503, 60041);
        assertThat(publicationState(dashboardId)).isEqualTo("0:null:0");
        assertThat(publicationAuditCount(dashboardId)).isZero();
    }

    /** 公共幂等层只保存完成墓碑：同正文10014、异正文10009，不重放201正文。 */
    @Test
    @DisplayName("看板发布使用可选公共幂等完成墓碑")
    void publicationUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = createPublishableDashboard(fixture, "发布幂等", "发布幂等");
        String path = versionsPath(fixture.projectId(), dashboardId);
        String key = "dashboard-publish-once";
        String body = publishEnvelope("0", "0");

        JsonNode published = created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(publishEnvelope("0", "1"))), 409, 10009);
        assertThat(publicationState(dashboardId))
                .isEqualTo("1:" + published.path("id").asString() + ":1");
        assertThat(publicationAuditCount(dashboardId)).isOne();
    }

    /** 首次发布在真实看板行锁后等待时，同键重试必须10010且不能进入第二次发布。 */
    @Test
    @DisplayName("看板发布对在途同键请求返回10010")
    void publicationReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = createPublishableDashboard(fixture, "发布在途", "发布在途");
        String path = versionsPath(fixture.projectId(), dashboardId);
        String body = publishEnvelope("0", "0");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM dash_dashboard WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, dashboardId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-publish-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);

            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-publish-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            created(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(publicationState(dashboardId)).startsWith("1:").endsWith(":1");
        assertThat(publicationAuditCount(dashboardId)).isOne();
    }

    /** OWNER与ADMIN均可在无幂等键时切回历史版本，目标快照保持不可变且只推进发布轴。 */
    @ParameterizedTest(name = "{0}可回滚看板历史版本")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色回滚看板并返回目标版本详情")
    void managementRolesRollbackDashboardVersion(ProjectRole role) throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(role), "成功回滚");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId());

        MvcResult result = write(rollback.fixture(), post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2")));
        JsonNode response = ok(result);

        assertThat(response).isEqualTo(rollback.firstVersion());
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2");
        assertThat(publicationAuditCount(rollback.dashboardId())).isEqualTo(2);
        assertThat(rollbackAuditCount(rollback.dashboardId())).isOne();
    }

    /** 只读角色、归档项目和不可见看板沿既有管理写边界拒绝，且不泄露目标版本存在性。 */
    @Test
    @DisplayName("看板回滚拒绝只读角色、归档项目和不可见目标")
    void rollbackPreservesAuthorizationLifecycleAndVisibilityErrors() throws Exception {
        for (ProjectRole role : List.of(ProjectRole.OPERATOR, ProjectRole.VIEWER)) {
            Fixture fixture = seed(role);
            UUID dashboardId = insertDashboard(
                    fixture, "只读回滚", 1, 0, Instant.now(), draftContent("只读回滚"), false);
            error(write(fixture, post(rollbackPath(fixture.projectId(), dashboardId, Uuid7.generate()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 403, 60035);
        }

        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedDashboard = insertDashboard(
                archived, "归档回滚", 1, 0, Instant.now(), draftContent("归档回滚"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(rollbackPath(
                archived.projectId(), archivedDashboard, Uuid7.generate()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 403, 50017);

        Fixture owner = seed(ProjectRole.OWNER);
        UUID deleted = insertDashboard(
                owner, "删除回滚", 1, 0, Instant.now(), draftContent("删除回滚"), true);
        for (UUID hidden : List.of(deleted, Uuid7.generate())) {
            error(write(owner, post(rollbackPath(owner.projectId(), hidden, Uuid7.generate()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 404, 60034);
        }

        Fixture nonMember = seed(ProjectRole.OWNER);
        RollbackFixture foreign = prepareRollbackFixture(seed(ProjectRole.OWNER), "非成员回滚");
        error(write(nonMember, post(rollbackPath(
                foreign.fixture().projectId(), foreign.dashboardId(), foreign.firstVersionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 404, 50001);
        assertThat(publicationState(foreign.dashboardId()))
                .isEqualTo("2:" + foreign.secondVersionId() + ":2");
        assertThat(rollbackAuditCount(foreign.dashboardId())).isZero();
    }

    /** 可见看板内不存在、属于其他看板或其他项目的版本统一60042，避免版本归属侧信道。 */
    @Test
    @DisplayName("看板回滚目标版本缺失或越界统一隐藏")
    void rollbackHidesMissingAndForeignTargetVersions() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "目标隐藏");
        Fixture sameProject = rollback.fixture();
        UUID otherDashboard = insertDashboard(
                sameProject, "同项目其他看板", 0, 0, Instant.now(), draftContent("其他看板"), false);
        UUID otherVersion = insertDashboardVersion(
                sameProject, otherDashboard, 1, 0, Instant.now(), JSON.readTree(validContent("其他版本")));
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignDashboard = insertDashboard(
                foreign, "其他项目看板", 0, 0, Instant.now(), draftContent("跨项目"), false);
        UUID foreignVersion = insertDashboardVersion(
                foreign, foreignDashboard, 1, 0, Instant.now(), JSON.readTree(validContent("跨项目版本")));

        for (UUID hiddenVersion : List.of(Uuid7.generate(), otherVersion, foreignVersion)) {
            error(write(sameProject, post(rollbackPath(
                    sameProject.projectId(), rollback.dashboardId(), hiddenVersion))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("2"))), 404, 60042);
        }
        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2");
        assertThat(rollbackAuditCount(rollback.dashboardId())).isZero();
    }

    /** publicationRevision必须是规范Long，陈旧、当前目标和计数耗尽均归可恢复的60040冲突。 */
    @Test
    @DisplayName("看板回滚区分非法revision与发布冲突")
    void rollbackDistinguishesInvalidRevisionAndPublicationConflicts() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "回滚Revision");
        String target = rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId());

        for (String invalid : List.of("", " 2", "-1", "01", "9223372036854775808")) {
            error(write(rollback.fixture(), post(target).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60039);
        }
        error(write(rollback.fixture(), post(target).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 409, 60040);
        error(write(rollback.fixture(), post(rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.secondVersionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 409, 60040);

        setDashboardPublicationRevision(rollback.dashboardId(), Long.MAX_VALUE);
        error(write(rollback.fixture(), post(target).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60040);
        assertThat(rollbackAuditCount(rollback.dashboardId())).isZero();
    }

    /** HTTP入口必须实际调用严格解析器，不能因普通DTO绑定而放过未知字段。 */
    @Test
    @DisplayName("看板回滚HTTP拒绝严格信封外字段")
    void rollbackHttpRejectsUnknownEnvelopeField() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "回滚严格信封");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId());

        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPublicationRevision\":\"2\",\"unknown\":true}")), 400, 10002);

        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2");
        assertThat(rollbackAuditCount(rollback.dashboardId())).isZero();
    }

    /** 历史版本必须按当前宿主资格重新验收；资格事实不可用时503失败关闭且不移动指针。 */
    @Test
    @DisplayName("看板回滚在宿主资格不可用时返回60041")
    void rollbackFailsClosedWhenHostQualificationIsUnavailable() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "回滚资格");
        when(hostQualifications.current()).thenReturn(Optional.empty());

        error(write(rollback.fixture(), post(rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 503, 60041);
        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2");
        assertThat(rollbackAuditCount(rollback.dashboardId())).isZero();
    }

    /** 公共幂等完成态仅保留墓碑：同正文10014、异正文10009，不能重放目标版本正文。 */
    @Test
    @DisplayName("看板回滚使用可选公共幂等完成墓碑")
    void rollbackUsesOptionalCommonIdempotencyTombstone() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "回滚幂等");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId());
        String body = publicationRevisionEnvelope("2");

        assertThat(ok(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-rollback-once").content(body))))
                .isEqualTo(rollback.firstVersion());
        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-rollback-once").content(body)), 409, 10014);
        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-rollback-once")
                .content(publicationRevisionEnvelope("3"))), 409, 10009);
        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2");
        assertThat(rollbackAuditCount(rollback.dashboardId())).isOne();
    }

    /** 首次回滚在真实看板行锁后等待时，同键重试必须10010且不能进入第二次CAS。 */
    @Test
    @DisplayName("看板回滚对在途同键请求返回10010")
    void rollbackReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        RollbackFixture rollback = prepareRollbackFixture(seed(ProjectRole.OWNER), "回滚在途");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.dashboardId(), rollback.firstVersionId());
        String body = publicationRevisionEnvelope("2");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM dash_dashboard WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, rollback.dashboardId());
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(rollback.fixture(), post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-rollback-in-progress")
                    .content(body)));
            awaitInProgressRecord(rollback.fixture().projectId(), "POST", path);

            error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-rollback-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            ok(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(publicationState(rollback.dashboardId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2");
        assertThat(rollbackAuditCount(rollback.dashboardId())).isOne();
    }

    /** OWNER与ADMIN可在无幂等键时撤回当前发布，204不返回正文或资源位置且只推进发布轴。 */
    @ParameterizedTest(name = "{0}可撤回看板当前发布")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色撤回看板并返回空204")
    void managementRolesWithdrawDashboardPublication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID dashboardId = preparePublishedDashboard(fixture, "成功撤回");

        MvcResult result = write(fixture, post(withdrawPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1")));

        noContent(result);
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(publicationAuditCount(dashboardId)).isOne();
        assertThat(withdrawalAuditCount(dashboardId)).isOne();
    }

    /** 只读角色、非成员、归档项目和不可见看板分别保持角色、项目与资源隐藏边界。 */
    @Test
    @DisplayName("看板撤回拒绝只读角色、非成员、归档项目和不可见目标")
    void withdrawalPreservesAuthorizationLifecycleAndVisibilityErrors() throws Exception {
        for (ProjectRole role : List.of(ProjectRole.OPERATOR, ProjectRole.VIEWER)) {
            Fixture fixture = seed(role);
            UUID dashboardId = insertDashboard(
                    fixture, "只读撤回", 1, 0, Instant.now(), draftContent("只读撤回"), false);
            error(write(fixture, post(withdrawPath(fixture.projectId(), dashboardId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 403, 60035);
        }

        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedDashboard = insertDashboard(
                archived, "归档撤回", 1, 0, Instant.now(), draftContent("归档撤回"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(withdrawPath(archived.projectId(), archivedDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 403, 50017);

        Fixture owner = seed(ProjectRole.OWNER);
        UUID deleted = insertDashboard(
                owner, "删除撤回", 1, 0, Instant.now(), draftContent("删除撤回"), true);
        for (UUID hidden : List.of(deleted, Uuid7.generate())) {
            error(write(owner, post(withdrawPath(owner.projectId(), hidden))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 404, 60034);
        }

        Fixture nonMember = seed(ProjectRole.OWNER);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignDashboard = insertDashboard(
                foreign, "非成员撤回", 1, 0, Instant.now(), draftContent("非成员撤回"), false);
        error(write(nonMember, post(withdrawPath(foreign.projectId(), foreignDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 404, 50001);
        assertThat(publicationState(foreignDashboard)).isEqualTo("1:null:0");
        assertThat(withdrawalAuditCount(foreignDashboard)).isZero();
    }

    /** 撤回HTTP必须实际接入严格共享解析器，未知字段不能被普通DTO绑定静默丢弃。 */
    @Test
    @DisplayName("看板撤回HTTP拒绝严格信封外字段")
    void withdrawalHttpRejectsUnknownEnvelopeField() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "撤回严格信封");

        error(write(fixture, post(withdrawPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPublicationRevision\":\"1\",\"unknown\":true}")), 400, 10002);

        assertThat(publicationState(dashboardId)).startsWith("1:").endsWith(":1");
        assertThat(withdrawalAuditCount(dashboardId)).isZero();
    }

    /** 撤回只接受规范Long；旧revision、空指针重复撤回和计数耗尽均沿60040拒绝。 */
    @Test
    @DisplayName("看板撤回区分非法revision与发布冲突")
    void withdrawalDistinguishesInvalidRevisionAndPublicationConflicts() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "撤回Revision");
        String path = withdrawPath(fixture.projectId(), dashboardId);

        for (String invalid : List.of("", " 1", "-1", "01", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60039);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 409, 60040);

        setDashboardPublicationRevision(dashboardId, Long.MAX_VALUE);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60040);
        setDashboardPublicationRevision(dashboardId, 1);

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 409, 60040);
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(withdrawalAuditCount(dashboardId)).isOne();
    }

    /** 公共幂等完成态不重放空成功：同正文10014、异正文10009且只有一次撤回审计。 */
    @Test
    @DisplayName("看板撤回使用可选公共幂等完成墓碑")
    void withdrawalUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "撤回幂等");
        String path = withdrawPath(fixture.projectId(), dashboardId);
        String body = publicationRevisionEnvelope("1");

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-withdraw-once").content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-withdraw-once").content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-withdraw-once")
                .content(publicationRevisionEnvelope("2"))), 409, 10009);
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(withdrawalAuditCount(dashboardId)).isOne();
    }

    /** 首次撤回在真实看板行锁后等待时，同键重试必须10010且只有首请求清空指针。 */
    @Test
    @DisplayName("看板撤回对在途同键请求返回10010")
    void withdrawalReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "撤回在途");
        String path = withdrawPath(fixture.projectId(), dashboardId);
        String body = publicationRevisionEnvelope("1");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM dash_dashboard WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, dashboardId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-withdraw-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);

            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-withdraw-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            noContent(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(withdrawalAuditCount(dashboardId)).isOne();
    }

    /** OWNER与ADMIN可无幂等键软删从未发布、已发布和已撤回目录，且204之后目录统一不可见。 */
    @ParameterizedTest(name = "{0}可软删各发布状态看板")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色软删各发布状态看板并返回空204")
    void managementRolesSoftDeleteEveryPublicationState(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID unpublished = insertDashboard(
                fixture, "删除从未发布", 0, 0, Instant.now(), draftContent("删除从未发布"), false);
        UUID published = preparePublishedDashboard(fixture, "删除已发布");
        UUID withdrawn = preparePublishedDashboard(fixture, "删除已撤回");
        noContent(write(fixture, post(withdrawPath(fixture.projectId(), withdrawn))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));

        noContent(write(fixture, post(softDeletePath(fixture.projectId(), unpublished))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))));
        noContent(write(fixture, post(softDeletePath(fixture.projectId(), published))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));
        noContent(write(fixture, post(softDeletePath(fixture.projectId(), withdrawn))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))));

        assertThat(publicationState(unpublished)).isEqualTo("1:null:0");
        assertThat(publicationState(published)).isEqualTo("2:null:1");
        assertThat(publicationState(withdrawn)).isEqualTo("3:null:1");
        assertThat(isDashboardDeleted(unpublished)).isTrue();
        assertThat(isDashboardDeleted(published)).isTrue();
        assertThat(isDashboardDeleted(withdrawn)).isTrue();
        assertThat(deletionAuditCount(unpublished)).isOne();
        assertThat(deletionAuditCount(published)).isOne();
        assertThat(deletionAuditCount(withdrawn)).isOne();
        for (UUID deleted : List.of(unpublished, published, withdrawn)) {
            error(authenticatedGet(fixture, itemPath(fixture.projectId(), deleted)), 404, 60034);
        }
        JsonNode page = ok(authenticatedGet(fixture, basePath(fixture.projectId())));
        assertThat(page.path("items").valueStream().map(item -> item.path("id").asString()).toList())
                .doesNotContain(unpublished.toString(), published.toString(), withdrawn.toString());
    }

    /** 只读角色、非成员、归档项目和不可见目录不能触发软删副作用。 */
    @Test
    @DisplayName("看板软删拒绝只读角色、非成员、归档项目和不可见目标")
    void softDeletePreservesAuthorizationLifecycleAndVisibilityErrors() throws Exception {
        for (ProjectRole role : List.of(ProjectRole.OPERATOR, ProjectRole.VIEWER)) {
            Fixture fixture = seed(role);
            UUID dashboardId = insertDashboard(
                    fixture, "只读删除", 1, 0, Instant.now(), draftContent("只读删除"), false);
            error(write(fixture, post(softDeletePath(fixture.projectId(), dashboardId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 403, 60035);
            assertThat(deletionAuditCount(dashboardId)).isZero();
        }

        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedDashboard = insertDashboard(
                archived, "归档删除", 1, 0, Instant.now(), draftContent("归档删除"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(softDeletePath(archived.projectId(), archivedDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 403, 50017);
        assertThat(deletionAuditCount(archivedDashboard)).isZero();

        Fixture owner = seed(ProjectRole.OWNER);
        UUID deleted = insertDashboard(
                owner, "已经删除", 1, 0, Instant.now(), draftContent("已经删除"), true);
        for (UUID hidden : List.of(deleted, Uuid7.generate())) {
            error(write(owner, post(softDeletePath(owner.projectId(), hidden))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("1"))), 404, 60034);
        }

        Fixture nonMember = seed(ProjectRole.OWNER);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignDashboard = insertDashboard(
                foreign, "非成员删除", 1, 0, Instant.now(), draftContent("非成员删除"), false);
        error(write(nonMember, post(softDeletePath(foreign.projectId(), foreignDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 404, 50001);
        assertThat(isDashboardDeleted(foreignDashboard)).isFalse();
        assertThat(deletionAuditCount(foreignDashboard)).isZero();
    }

    /** 软删HTTP必须复用严格单revision解析器，未知字段不能被DTO绑定静默丢弃。 */
    @Test
    @DisplayName("看板软删HTTP拒绝严格信封外字段")
    void softDeleteHttpRejectsUnknownEnvelopeField() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "软删严格信封");

        error(write(fixture, post(softDeletePath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPublicationRevision\":\"1\",\"unknown\":true}")), 400, 10002);

        assertThat(isDashboardDeleted(dashboardId)).isFalse();
        assertThat(deletionAuditCount(dashboardId)).isZero();
    }

    /** 软删只接受规范Long；旧revision和计数耗尽均沿发布CAS冲突60040拒绝。 */
    @Test
    @DisplayName("看板软删区分非法revision与发布冲突")
    void softDeleteDistinguishesInvalidRevisionAndPublicationConflicts() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "软删Revision");
        String path = softDeletePath(fixture.projectId(), dashboardId);

        for (String invalid : List.of("", " 1", "-1", "01", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60039);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 409, 60040);

        setDashboardPublicationRevision(dashboardId, Long.MAX_VALUE);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60040);

        assertThat(isDashboardDeleted(dashboardId)).isFalse();
        assertThat(deletionAuditCount(dashboardId)).isZero();
    }

    /** 公共幂等完成态不重放空成功：同正文10014、异正文10009且只有一次删除审计。 */
    @Test
    @DisplayName("看板软删使用可选公共幂等完成墓碑")
    void softDeleteUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "软删幂等");
        String path = softDeletePath(fixture.projectId(), dashboardId);
        String body = publicationRevisionEnvelope("1");

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-soft-delete-once").content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-soft-delete-once").content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-soft-delete-once")
                .content(publicationRevisionEnvelope("2"))), 409, 10009);
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(isDashboardDeleted(dashboardId)).isTrue();
        assertThat(deletionAuditCount(dashboardId)).isOne();
    }

    /** 首次软删在真实看板行锁后等待时，同键重试必须10010且只有首请求删除目录。 */
    @Test
    @DisplayName("看板软删对在途同键请求返回10010")
    void softDeleteReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = preparePublishedDashboard(fixture, "软删在途");
        String path = softDeletePath(fixture.projectId(), dashboardId);
        String body = publicationRevisionEnvelope("1");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM dash_dashboard WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, dashboardId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-soft-delete-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);

            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-soft-delete-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            noContent(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(publicationState(dashboardId)).isEqualTo("2:null:1");
        assertThat(isDashboardDeleted(dashboardId)).isTrue();
        assertThat(deletionAuditCount(dashboardId)).isOne();
    }

    /** OWNER与ADMIN首次创建和同语义重放均恢复唯一不可变身份，且不重复持久副作用。 */
    @ParameterizedTest(name = "{0}可幂等创建看板")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色创建看板并稳定重放不可变回执")
    void managementRolesCreateDashboardWithStableReceipt(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        String key = "dashboard-create-stable-" + role.name().toLowerCase();
        String content = validContent("初始草稿");
        MvcResult firstResult = write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(createEnvelope("初始看板", content)));
        JsonNode first = created(firstResult);
        UUID dashboardId = UUID.fromString(first.path("id").asString());

        assertThat(fields(first)).containsExactlyInAnyOrder("id", "createdAt");
        assertThat(first.path("createdAt").asString()).endsWith("Z");
        assertThat(firstResult.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(itemPath(fixture.projectId(), dashboardId));
        assertThat(dashboardName(dashboardId)).isEqualTo("初始看板");
        assertThat(draftRevision(dashboardId)).isZero();

        // 创建回执不含可变名称；后续改名不能改变旧请求恢复的创建身份。
        ok(write(fixture, patch(itemPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"创建后改名\"}")));

        // 外层顺序/空白与名称等价JSON转义不改变领域请求摘要；content内部字节仍保持精确一致。
        MvcResult replayResult = write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(" { \"content\" : " + content
                        + ", \"managementName\" : \"\\u521d始看板\" } "));
        JsonNode replay = created(replayResult);

        assertThat(replay).isEqualTo(first);
        assertThat(replayResult.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(itemPath(fixture.projectId(), dashboardId));
        assertDashboardCreationFacts(fixture, 1);
    }

    /** 同一身份与键绑定名称和content；任一变化以及无效键都不能产生第二个看板。 */
    @Test
    @DisplayName("看板创建拒绝同键异请求和无效幂等键")
    void rejectsChangedRequestAndInvalidDashboardCreationKey() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());
        String key = "dashboard-create-conflict";
        String content = validContent("原始草稿");
        String body = createEnvelope("原始看板", content);

        created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(createEnvelope("变化看板", content))), 409, 10009);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(createEnvelope("原始看板", validContent("变化草稿")))), 409, 10009);

        for (String invalidKey : List.of("   ", "k".repeat(129))) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", invalidKey).content(body)), 400, 10001);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10001);
        assertDashboardCreationFacts(fixture, 1);
    }

    /** 创建外层、Title、草稿与模型错误各自保持既有稳定分类，失败不得留下恢复映射。 */
    @Test
    @DisplayName("看板创建精确区分外层和领域校验错误")
    void distinguishesDashboardCreationValidationFailures() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());

        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-create-envelope")
                .content("{\"managementName\":\"甲\",\"content\":{},\"unknown\":true}")), 400, 10002);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-create-title")
                .content(createEnvelope(" \t ", validContent("合法草稿")))), 400, 10001);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-create-draft")
                .content(createEnvelope("草稿错误", "{\"schemaVersion\":\"tc.dashboard/v1\","
                        + "\"schemaVersion\":\"tc.dashboard/v1\"}"))), 400, 60036);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-create-model")
                .content(createEnvelope("模型错误", contentWithModel(Uuid7.generate())))), 400, 60038);

        assertDashboardCreationFacts(fixture, 0);
    }

    /** 只读角色和ARCHIVED项目必须在领域创建前失败，不产生目录、草稿、映射或审计。 */
    @Test
    @DisplayName("权限与项目生命周期拒绝看板创建")
    void rejectsUnauthorizedAndArchivedDashboardCreation() throws Exception {
        for (ProjectRole role : List.of(ProjectRole.OPERATOR, ProjectRole.VIEWER)) {
            Fixture fixture = seed(role);
            error(write(fixture, post(basePath(fixture.projectId())).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-create-forbidden-" + role.name().toLowerCase())
                    .content(createEnvelope("越权看板", validContent("越权草稿")))), 403, 60035);
            assertDashboardCreationFacts(fixture, 0);
        }

        Fixture archived = seed(ProjectRole.OWNER);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(basePath(archived.projectId())).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-create-archived")
                .content(createEnvelope("归档看板", validContent("归档草稿")))), 403, 50017);
        assertDashboardCreationFacts(archived, 0);
    }

    /** 创建结果软删后必须保留映射并返回10014，不能用旧键重建替代身份。 */
    @Test
    @DisplayName("软删后的看板创建重放返回10014且不重建")
    void deletedDashboardCreationResultCannotBeReplayed() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());
        String key = "dashboard-create-deleted";
        String body = createEnvelope("待删看板", validContent("待删草稿"));
        JsonNode first = created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        UUID dashboardId = UUID.fromString(first.path("id").asString());
        softDeleteDashboard(dashboardId);

        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);

        assertDashboardCreationFacts(fixture, 1);
        assertThat(isDashboardDeleted(dashboardId)).isTrue();
    }

    /** OWNER与ADMIN可修改目录名称并以字符串revision完整替换草稿。 */
    @ParameterizedTest(name = "{0}可修改看板名称和草稿")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色可改名并保存看板草稿")
    void managementRolesRenameAndSaveDraft(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID dashboardId = insertDashboard(
                fixture, "旧名称", 0, 0, Instant.now(), draftContent("旧草稿"), false);

        JsonNode renamed = ok(write(fixture, patch(itemPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\" 新名称 \"}")));
        assertThat(renamed.path("managementName").asString()).isEqualTo(" 新名称 ");

        String content = validContent("新草稿");
        JsonNode saved = ok(write(fixture, put(draftPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", content))));
        assertThat(fields(saved)).containsExactlyInAnyOrder("dashboardId", "content", "revision", "updatedAt");
        assertThat(saved.path("dashboardId").asString()).isEqualTo(dashboardId.toString());
        assertThat(saved.path("revision").isString()).isTrue();
        assertThat(saved.path("revision").asString()).isEqualTo("1");
        assertThat(saved.path("content").path("pages").get(0).path("title").asString()).isEqualTo("新草稿");
        assertThat(saved.path("content").path("presentation").path("mode").asString())
                .isEqualTo("RESPONSIVE_GRID");
        assertThat(saved.path("content").path("presentation").path("gap").asInt()).isEqualTo(8);
    }

    /** OPERATOR与VIEWER可读但不可写，两个入口都必须使用看板领域稳定403。 */
    @ParameterizedTest(name = "{0}不能修改看板")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能改名或保存看板草稿")
    void readOnlyRolesCannotMutateDashboard(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID dashboardId = insertDashboard(
                fixture, "只读名称", 0, 0, Instant.now(), draftContent("只读草稿"), false);

        error(write(fixture, patch(itemPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"越权名称\"}")), 403, 60035);
        error(write(fixture, put(draftPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent("越权草稿")))), 403, 60035);

        assertThat(dashboardName(dashboardId)).isEqualTo("只读名称");
        assertThat(draftRevision(dashboardId)).isZero();
    }

    /** ACTIVE许可和资源隐藏必须沿既有服务边界映射，HTTP不得另造状态。 */
    @Test
    @DisplayName("归档和不可见看板拒绝写入且事实不漂移")
    void rejectsArchivedAndInvisibleDashboardWrites() throws Exception {
        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedDashboard = insertDashboard(
                archived, "归档名称", 0, 0, Instant.now(), draftContent("归档草稿"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, patch(itemPath(archived.projectId(), archivedDashboard))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"禁止名称\"}")), 403, 50017);
        assertThat(dashboardName(archivedDashboard)).isEqualTo("归档名称");

        Fixture active = seed(ProjectRole.OWNER);
        UUID deleted = insertDashboard(
                active, "已删名称", 0, 0, Instant.now(), draftContent("已删草稿"), true);
        error(write(active, patch(itemPath(active.projectId(), deleted))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"不可见名称\"}")), 404, 60034);
        error(write(active, put(draftPath(active.projectId(), Uuid7.generate()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent("缺失草稿")))), 404, 60034);
        assertThat(dashboardName(deleted)).isEqualTo("已删名称");
    }

    /** Title、外层信封、草稿合同、CAS与模型资格必须保持互不吞并的稳定错误族。 */
    @Test
    @DisplayName("看板写请求精确区分10001、10002与60036至60038")
    void distinguishesWriteValidationFailures() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = insertDashboard(
                fixture, "校验名称", 0, 0, Instant.now(), draftContent("校验草稿"), false);
        String item = itemPath(fixture.projectId(), dashboardId);
        String draft = draftPath(fixture.projectId(), dashboardId);

        error(write(fixture, patch(item).contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\" \\t \"}")), 400, 10001);
        for (String body : List.of(
                "{\"managementName\":\"甲\",\"unknown\":true}",
                "{\"managementName\":\"甲\",\"managementName\":\"乙\"}")) {
            error(write(fixture, patch(item).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10002);
        }
        error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"content\":" + validContent("数字修订") + "}")),
                400, 10002);
        for (String revision : List.of("00", "-1", "9223372036854775808")) {
            error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                    .content(saveEnvelope(revision, validContent("非法修订")))), 400, 60036);
        }
        error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("1", validContent("陈旧修订")))), 409, 60037);
        error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", contentWithModel(Uuid7.generate())))), 400, 60038);

        UUID exhausted = insertDashboard(
                fixture, "耗尽名称", 0, Long.MAX_VALUE, Instant.now(), draftContent("耗尽草稿"), false);
        error(write(fixture, put(draftPath(fixture.projectId(), exhausted))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope(Long.toString(Long.MAX_VALUE), validContent("耗尽修订")))), 409, 60037);
        assertThat(dashboardName(dashboardId)).isEqualTo("校验名称");
        assertThat(draftRevision(dashboardId)).isZero();
        assertThat(draftRevision(exhausted)).isEqualTo(Long.MAX_VALUE);
    }

    /** content原文切片必须在建树前保留重复键与空白字节，交给看板草稿合同精确拒绝。 */
    @Test
    @DisplayName("看板草稿content保留原始字节和重复键证据")
    void preservesRawContentEvidenceForDraftValidation() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = insertDashboard(
                fixture, "原文名称", 0, 0, Instant.now(), draftContent("原文草稿"), false);
        String path = draftPath(fixture.projectId(), dashboardId);
        String duplicate = """
                {"expectedRevision":"0","content":{
                  "schemaVersion":"tc.dashboard/v1","schemaVersion":"tc.dashboard/v1"}}""";
        String valid = validContent("超长草稿");
        String whitespaceExpanded = "{\"expectedRevision\":\"0\",\"content\":"
                + valid.substring(0, 1) + " ".repeat(512_001) + valid.substring(1) + "}";

        JsonNode duplicateError = error(
                write(fixture, put(path).contentType(MediaType.APPLICATION_JSON).content(duplicate)), 400, 60036);
        JsonNode oversizedError = error(
                write(fixture, put(path).contentType(MediaType.APPLICATION_JSON).content(whitespaceExpanded)),
                400, 60036);
        assertThat(duplicateError.path("details").toString()).contains("DUPLICATE_KEY@$");
        assertThat(oversizedError.path("details").toString()).contains("RAW_TOO_LARGE@$");
        assertThat(draftRevision(dashboardId)).isZero();
    }

    /** PATCH与PUT沿公共墓碑处理，同请求完成后由已有GET恢复当前事实。 */
    @Test
    @DisplayName("看板写入口执行公共幂等并由GET恢复")
    void appliesCommonIdempotencyContractAndRecoversWithReads() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = insertDashboard(
                fixture, "幂等名称", 0, 0, Instant.now(), draftContent("幂等草稿"), false);
        String draft = draftPath(fixture.projectId(), dashboardId);
        String draftBody = saveEnvelope("0", validContent("一次保存"));

        ok(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-draft-once").content(draftBody)));
        error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-draft-once").content(draftBody)), 409, 10014);
        JsonNode recoveredDraft = ok(authenticatedGet(fixture, draft));
        assertThat(recoveredDraft.path("revision").asString()).isEqualTo("1");
        assertThat(recoveredDraft.path("content").path("pages").get(0).path("title").asString())
                .isEqualTo("一次保存");
        assertThat(recoveredDraft.path("content").path("presentation").path("gap").asInt()).isEqualTo(8);

        String item = itemPath(fixture.projectId(), dashboardId);
        ok(write(fixture, patch(item).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-rename-once")
                .content("{\"managementName\":\"首次名称\"}")));
        error(write(fixture, patch(item).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dashboard-rename-once")
                .content("{\"managementName\":\"冲突名称\"}")), 409, 10009);
        assertThat(ok(authenticatedGet(fixture, item)).path("managementName").asString()).isEqualTo("首次名称");
        assertThat(draftRevision(dashboardId)).isOne();
    }

    /** 首次请求尚在真实业务行锁后等待时，同键重试必须返回10010且不能进入第二次CAS。 */
    @Test
    @DisplayName("看板写入口对在途同键请求返回10010")
    void reportsInProgressForConcurrentIdempotentWrite() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID dashboardId = insertDashboard(
                fixture, "在途名称", 0, 0, Instant.now(), draftContent("在途草稿"), false);
        String path = draftPath(fixture.projectId(), dashboardId);
        String body = saveEnvelope("0", validContent("在途保存"));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM dash_dashboard WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, dashboardId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, put(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-draft-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), path);

            error(write(fixture, put(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "dashboard-draft-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            ok(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(draftRevision(dashboardId)).isOne();
    }

    /** 断言目录响应字段、Long字符串和服务端归属字段均不可见。 */
    private static void assertCatalog(
            JsonNode item, UUID dashboardId, String managementName, long publicationRevision) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "managementName", "publicationRevision", "currentVersionId", "createdAt", "updatedAt");
        assertThat(item.path("id").asString()).isEqualTo(dashboardId.toString());
        assertThat(item.path("managementName").asString()).isEqualTo(managementName);
        assertThat(item.path("publicationRevision").isString()).isTrue();
        assertThat(item.path("publicationRevision").asString()).isEqualTo(Long.toString(publicationRevision));
        assertThat(item.path("currentVersionId").isNull()).isTrue();
        assertThat(item.path("createdAt").asString()).endsWith("Z");
        assertThat(item.path("updatedAt").asString()).endsWith("Z");
        assertThat(item.has("tenantId")).isFalse();
        assertThat(item.has("projectId")).isFalse();
        assertThat(item.has("createdBy")).isFalse();
        assertThat(item.has("updatedBy")).isFalse();
        assertThat(item.has("deletedAt")).isFalse();
    }

    /** 断言历史目录只公开固定大小摘要，并把所有Long安全投影为十进制字符串。 */
    private static void assertVersionSummary(
            JsonNode item, UUID versionId, long versionNumber, long sourceDraftRevision, Instant publishedAt) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "publishedAt");
        assertThat(item.path("id").asString()).isEqualTo(versionId.toString());
        assertThat(item.path("versionNumber").isString()).isTrue();
        assertThat(item.path("versionNumber").asString()).isEqualTo(Long.toString(versionNumber));
        assertThat(item.path("sourceDraftRevision").isString()).isTrue();
        assertThat(item.path("sourceDraftRevision").asString()).isEqualTo(Long.toString(sourceDraftRevision));
        assertThat(item.path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        assertThat(item.path("schemaDigestAlgorithm").asString())
                .isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(item.path("schemaDigest").asString()).matches("[0-9a-f]{64}");
        assertThat(item.path("publishedAt").asString()).isEqualTo(publishedAt.toString());
    }

    /** 详情只在摘要上增加不可变Schema和两个派生清单，不公开归属、操作者或持久关系。 */
    private static void assertVersionDetail(
            JsonNode item, UUID versionId, long versionNumber, long sourceDraftRevision,
            Instant publishedAt, JsonNode schema) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "schema", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "requiredComponents", "requiredResources",
                "publishedAt");
        assertThat(item.path("id").asString()).isEqualTo(versionId.toString());
        assertThat(item.path("versionNumber").isString()).isTrue();
        assertThat(item.path("versionNumber").asString()).isEqualTo(Long.toString(versionNumber));
        assertThat(item.path("sourceDraftRevision").isString()).isTrue();
        assertThat(item.path("sourceDraftRevision").asString()).isEqualTo(Long.toString(sourceDraftRevision));
        assertThat(item.path("schema")).isEqualTo(schema);
        assertThat(item.path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        assertThat(item.path("schemaDigestAlgorithm").asString())
                .isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(item.path("schemaDigest").asString()).matches("[0-9a-f]{64}");
        assertThat(item.path("requiredComponents")).containsExactly(JSON.valueToTree("line-chart"));
        assertThat(item.path("requiredResources")).containsExactly(JSON.valueToTree("builtin://background"));
        assertThat(item.path("publishedAt").asString()).isEqualTo(publishedAt.toString());
    }

    /** 断言HTTP发布返回新版本完整详情，空组件画布不得伪造派生需求。 */
    private static void assertPublishedVersion(
            JsonNode item, UUID versionId, long versionNumber, long sourceDraftRevision, JsonNode schema) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "schema", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "requiredComponents", "requiredResources",
                "publishedAt");
        assertThat(item.path("id").asString()).isEqualTo(versionId.toString());
        assertThat(item.path("versionNumber").isString()).isTrue();
        assertThat(item.path("versionNumber").asString()).isEqualTo(Long.toString(versionNumber));
        assertThat(item.path("sourceDraftRevision").isString()).isTrue();
        assertThat(item.path("sourceDraftRevision").asString()).isEqualTo(Long.toString(sourceDraftRevision));
        assertThat(item.path("schema")).isEqualTo(schema);
        assertThat(item.path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        assertThat(item.path("schemaDigestAlgorithm").asString())
                .isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(item.path("schemaDigest").asString()).matches("[0-9a-f]{64}");
        assertThat(item.path("requiredComponents")).isEmpty();
        assertThat(item.path("requiredResources")).isEmpty();
        assertThat(item.path("publishedAt").asString()).endsWith("Z");
    }

    /** 取得对象字段集合，确保新增公开字段先经过合同变更。 */
    private static Set<String> fields(JsonNode node) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    /** 真实JWT携带当前项目，HTTP过滤器仍会回库复核成员关系。 */
    private String token(Fixture fixture) {
        return "Bearer " + tokens.issue(new AuthenticatedPrincipal(
                fixture.accountId(), fixture.tenantId(), fixture.projectId())).value();
    }

    /** 使用指定身份调用真实Spring MVC。 */
    private MvcResult authenticatedGet(Fixture fixture, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token(fixture))).andReturn();
    }

    /** 使用指定身份调用真实写过滤链；请求构造器保留调用方给出的精确原文字节。 */
    private MvcResult write(Fixture fixture, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token(fixture))).andReturn();
    }

    /** 成功响应必须精确是200。 */
    private static JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getResponse().getContentAsString());
    }

    /** 创建成功和同请求恢复都必须精确返回201。 */
    private static JsonNode created(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(201);
        return JSON.readTree(response.getResponse().getContentAsString());
    }

    /** 无资源投影的生命周期动作必须精确返回无正文、无Location的204。 */
    private static void noContent(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(response.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(response.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
    }

    /** 同时钉住HTTP类别与稳定业务码。 */
    private static JsonNode error(MvcResult response, int status, int code) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(code);
        return body;
    }

    /** 当前项目看板集合根路径。 */
    private static String basePath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/dashboards";
    }

    /** 当前项目单个看板目录路径。 */
    private static String itemPath(UUID projectId, UUID dashboardId) {
        return basePath(projectId) + "/" + dashboardId;
    }

    /** 当前项目单个看板草稿路径。 */
    private static String draftPath(UUID projectId, UUID dashboardId) {
        return itemPath(projectId, dashboardId) + "/draft";
    }

    /** 当前项目指定看板的不可变版本集合路径。 */
    private static String versionsPath(UUID projectId, UUID dashboardId) {
        return itemPath(projectId, dashboardId) + "/versions";
    }

    /** 当前项目指定看板的精确不可变版本路径。 */
    private static String versionPath(UUID projectId, UUID dashboardId, UUID versionId) {
        return versionsPath(projectId, dashboardId) + "/" + versionId;
    }

    /** 当前项目指定看板的历史版本回滚动作路径。 */
    private static String rollbackPath(UUID projectId, UUID dashboardId, UUID versionId) {
        return versionPath(projectId, dashboardId, versionId) + "/rollback";
    }

    /** 当前项目指定看板的发布撤回动作路径。 */
    private static String withdrawPath(UUID projectId, UUID dashboardId) {
        return itemPath(projectId, dashboardId) + "/withdraw";
    }

    /** 返回看板软删入口，确保公共幂等记录与Controller使用完全相同的规范路径。 */
    private static String softDeletePath(UUID projectId, UUID dashboardId) {
        return itemPath(projectId, dashboardId) + "/soft-delete";
    }

    /** 生成不含模型引用的最小合法看板content原文。 */
    private static String validContent(String title) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\","
                + "\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},"
                + "\"models\":[],\"variables\":[],"
                + "\"pages\":[{\"id\":\"overview\",\"title\":\"" + title
                + "\",\"components\":[]}]}";
    }

    /** 生成引用缺失模型的结构合法content，用于证明60038不被草稿结构错误吞并。 */
    private static String contentWithModel(UUID versionId) {
        return "{\"schemaVersion\":\"tc.dashboard/v1\","
                + "\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},"
                + "\"models\":[{\"key\":\"missing_model\",\"versionId\":\"" + versionId
                + "\",\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\","
                + "\"digest\":\"" + "a".repeat(64) + "\","
                + "\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}],"
                + "\"variables\":[],\"pages\":[{\"id\":\"overview\","
                + "\"title\":\"缺失模型\",\"components\":[]}]}";
    }

    /** 生成草稿保存严格信封，保留content文本而不经JSON树重写。 */
    private static String saveEnvelope(String expectedRevision, String content) {
        return "{\"expectedRevision\":\"" + expectedRevision + "\",\"content\":" + content + "}";
    }

    /** 生成发布严格信封，两个revision均保持调用方给出的字符串表示。 */
    private static String publishEnvelope(String expectedDraftRevision, String expectedPublicationRevision) {
        return "{\"expectedDraftRevision\":" + JSON.valueToTree(expectedDraftRevision)
                + ",\"expectedPublicationRevision\":" + JSON.valueToTree(expectedPublicationRevision) + "}";
    }

    /** 生成发布生命周期严格信封，publicationRevision保持调用方给出的字符串表示。 */
    private static String publicationRevisionEnvelope(String expectedPublicationRevision) {
        return "{\"expectedPublicationRevision\":" + JSON.valueToTree(expectedPublicationRevision) + "}";
    }

    /** 生成创建严格信封，content保持调用方提供的原始文本。 */
    private static String createEnvelope(String managementName, String content) {
        return "{\"managementName\":" + JSON.valueToTree(managementName) + ",\"content\":" + content + "}";
    }

    /** 经真实创建HTTP保存校验后的规范草稿，避免数据库夹具绕过默认值归一化后伪造发布失败。 */
    private UUID createPublishableDashboard(Fixture fixture, String managementName, String title) throws Exception {
        JsonNode receipt = created(write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "publish-fixture-" + Uuid7.generate())
                .content(createEnvelope(managementName, validContent(title)))));
        return UUID.fromString(receipt.path("id").asString());
    }

    /**
     * 通过真实HTTP连续发布两个合格版本，为回滚入口提供当前指针和历史目标。
     *
     * <p>夹具不直写当前指针，确保测试前置同样经过严格信封、资格校验和数据库CAS。</p>
     */
    private RollbackFixture prepareRollbackFixture(Fixture fixture, String title) throws Exception {
        UUID dashboardId = createPublishableDashboard(fixture, title, title + "版本一");
        JsonNode first = created(write(fixture, post(versionsPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));

        ok(write(fixture, put(draftPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent(title + "版本二")))));
        JsonNode second = created(write(fixture, post(versionsPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("1", "1"))));

        return new RollbackFixture(
                fixture,
                dashboardId,
                UUID.fromString(first.path("id").asString()),
                first,
                UUID.fromString(second.path("id").asString()));
    }

    /** 经真实创建和发布HTTP形成非空当前指针，避免撤回正向夹具绕过发布事务。 */
    private UUID preparePublishedDashboard(Fixture fixture, String title) throws Exception {
        UUID dashboardId = createPublishableDashboard(fixture, title, title + "版本");
        created(write(fixture, post(versionsPath(fixture.projectId(), dashboardId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        return dashboardId;
    }

    /** 每例建立唯一账号、租户和项目，避免共享容器内跨测试数据相撞。 */
    private Fixture seed(ProjectRole role) throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID ownerId = role == ProjectRole.OWNER ? accountId : Uuid7.generate();
        UUID projectId = Uuid7.generate();
        fixtureProjectIds.add(projectId);
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenantId, "看板接口租户");
            execute(connection, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", tenantId);
            insertAccount(connection, tenantId, accountId, "看板接口账号");
            if (!ownerId.equals(accountId)) {
                insertAccount(connection, tenantId, ownerId, "看板接口所有者");
            }
            execute(connection, """
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key)
                    VALUES (?,?,'看板接口项目','sh-1',?)
                    """, projectId, tenantId, "dashapi_" + projectId.toString().replace("-", ""));
            execute(connection, """
                    INSERT INTO sys_project_member(id,project_id,account_id,role)
                    VALUES (?,?,?,'OWNER')
                    """, Uuid7.generate(), projectId, ownerId);
            if (!ownerId.equals(accountId)) {
                execute(connection, """
                        INSERT INTO sys_project_member(id,project_id,account_id,role)
                        VALUES (?,?,?,?)
                        """, Uuid7.generate(), projectId, accountId, role.name());
            }
        }
        return new Fixture(tenantId, projectId, accountId);
    }

    /** 同时建立账号和租户成员，满足生产外键与Console身份事实。 */
    private static void insertAccount(
            Connection connection, UUID tenantId, UUID accountId, String displayName) throws SQLException {
        execute(connection, """
                INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                VALUES (?,?,'{noop}unused',?,clock_timestamp())
                """, accountId, accountId + "@example.com", displayName);
        execute(connection, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), tenantId, accountId);
    }

    /** 以服务端生成ID建立一个目录及其唯一草稿。 */
    private UUID insertDashboard(
            Fixture fixture,
            String name,
            long publicationRevision,
            long draftRevision,
            Instant updatedAt,
            JsonNode content,
            boolean deleted) throws SQLException {
        UUID dashboardId = Uuid7.generate();
        insertDashboard(fixture, dashboardId, name, publicationRevision,
                draftRevision, updatedAt, content, deleted);
        return dashboardId;
    }

    /** 同一连接插入目录与草稿，时间和ID由测试控制以证明键集分页。 */
    private void insertDashboard(
            Fixture fixture,
            UUID dashboardId,
            String name,
            long publicationRevision,
            long draftRevision,
            Instant updatedAt,
            JsonNode content,
            boolean deleted) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            execute(connection, """
                    INSERT INTO dash_dashboard(
                        id,tenant_id,project_id,management_name,publication_revision,
                        created_by,updated_by,created_at,updated_at,deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, dashboardId, fixture.tenantId(), fixture.projectId(), name,
                    publicationRevision, fixture.accountId(), fixture.accountId(),
                    updatedAt.minusSeconds(1), updatedAt, deleted ? updatedAt.plusSeconds(1) : null);
            execute(connection, """
                    INSERT INTO dash_dashboard_draft(
                        dashboard_id,tenant_id,project_id,content,revision,updated_by,created_at,updated_at)
                    VALUES (?,?,?,?::jsonb,?,?,?,?)
                    """, dashboardId, fixture.tenantId(), fixture.projectId(), content.toString(), draftRevision,
                    fixture.accountId(), updatedAt.minusSeconds(1), updatedAt);
            connection.commit();
        }
    }

    /** 以迁移owner插入一条无模型关系的不可变版本，HTTP读取仍走普通运行身份与RLS。 */
    private UUID insertDashboardVersion(
            Fixture fixture,
            UUID dashboardId,
            long versionNumber,
            long sourceDraftRevision,
            Instant publishedAt,
            JsonNode schema) throws SQLException {
        UUID versionId = Uuid7.generate();
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, """
                    INSERT INTO dash_dashboard_version(
                        id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                        schema,schema_version,schema_digest_algorithm,schema_digest,
                        required_components,required_resources,published_by_account_id,published_at)
                    VALUES (?,?,?,?,?,?,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',?,
                            '["line-chart"]'::jsonb,'["builtin://background"]'::jsonb,?,?)
                    """, versionId, fixture.tenantId(), fixture.projectId(), dashboardId,
                    versionNumber, sourceDraftRevision, schema.toString(), "d".repeat(64),
                    fixture.accountId(), publishedAt);
        }
        return versionId;
    }

    /** 最小读取夹具只需满足已持久化的tc.dashboard/v1根合同。 */
    private static JsonNode draftContent(String title) {
        return JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1").put("title", title);
    }

    /** 参数化SQL夹具，避免字符串拼接影响JSON或身份字段。 */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                Object value = arguments[index];
                statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
            }
            statement.executeUpdate();
        }
    }

    /** 设置项目生命周期状态，验证归档项目的只读边界。 */
    private void setProjectStatus(UUID projectId, String status) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE sys_project SET status=? WHERE id=?", status, projectId);
        }
    }

    /** 只为Long耗尽反例设置发布轴，历史版本和当前指针保持不变。 */
    private void setDashboardPublicationRevision(UUID dashboardId, long revision) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE dash_dashboard SET publication_revision=? WHERE id=?", revision, dashboardId);
        }
    }

    /** 从真实持久事实读取管理名称，拒绝用HTTP缓存响应猜测是否发生越权写入。 */
    private String dashboardName(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT management_name FROM dash_dashboard WHERE id=?")) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    /** 从真实持久事实读取草稿revision，证明拒绝和幂等重放均没有第二次写。 */
    private long draftRevision(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revision FROM dash_dashboard_draft WHERE dashboard_id=?")) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 读取发布revision、当前指针和版本数量，证明成功或拒绝后的真实持久状态。 */
    private String publicationState(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT dashboard.publication_revision, dashboard.current_version_id,
                            count(version.id) AS version_count
                       FROM dash_dashboard dashboard
                       LEFT JOIN dash_dashboard_version version ON version.dashboard_id=dashboard.id
                      WHERE dashboard.id=?
                      GROUP BY dashboard.publication_revision, dashboard.current_version_id
                     """)) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                UUID currentVersionId = result.getObject("current_version_id", UUID.class);
                return result.getLong("publication_revision") + ":" + currentVersionId
                        + ":" + result.getLong("version_count");
            }
        }
    }

    /** 读取指定看板的发布审计数量，证明HTTP重试和失败没有制造重复或半成品审计。 */
    private long publicationAuditCount(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM sys_audit_log
                      WHERE target_type='dashboard' AND target_id=? AND action='dashboard.published'
                     """)) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 读取指定看板的回滚审计数量，证明成功唯一且拒绝路径没有副作用。 */
    private long rollbackAuditCount(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM sys_audit_log
                      WHERE target_type='dashboard' AND target_id=? AND action='dashboard.rolled_back'
                     """)) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 读取指定看板的撤回审计数量，证明204提交唯一且拒绝路径没有副作用。 */
    private long withdrawalAuditCount(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM sys_audit_log
                      WHERE target_type='dashboard' AND target_id=? AND action='dashboard.withdrawn'
                     """)) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 读取指定看板的软删审计数量，证明目录隐藏与唯一审计在同一提交中发生。 */
    private long deletionAuditCount(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                       FROM sys_audit_log
                      WHERE target_type='dashboard' AND target_id=? AND action='dashboard.deleted'
                     """)) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 断言本项目领域幂等创建的四类持久事实和唯一创建审计数量完全一致。 */
    private void assertDashboardCreationFacts(Fixture fixture, long expected) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT (SELECT count(*) FROM dash_dashboard WHERE project_id=?) AS dashboards,
                            (SELECT count(*) FROM dash_dashboard_draft WHERE project_id=?) AS drafts,
                            (SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?) AS mappings,
                            (SELECT count(*) FROM sys_audit_log
                              WHERE project_id=? AND target_type='dashboard'
                                AND action='dashboard.created') AS audits
                     """)) {
            for (int index = 1; index <= 4; index++) {
                statement.setObject(index, fixture.projectId());
            }
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("dashboards")).isEqualTo(expected);
                assertThat(result.getLong("drafts")).isEqualTo(expected);
                assertThat(result.getLong("mappings")).isEqualTo(expected);
                assertThat(result.getLong("audits")).isEqualTo(expected);
            }
        }
    }

    /** 夹具直接落软删时刻，保留创建映射以验证领域墓碑恢复行为。 */
    private void softDeleteDashboard(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE dash_dashboard SET deleted_at=clock_timestamp() WHERE id=?", dashboardId);
        }
    }

    /** 从真实目录行观察软删事实，避免用HTTP隐藏响应推断持久状态。 */
    private boolean isDashboardDeleted(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT deleted_at IS NOT NULL FROM dash_dashboard WHERE id=?")) {
            statement.setObject(1, dashboardId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    /** 等待公共幂等抢占提交后再发重试，避免用线程启动顺序猜测请求已到达过滤器。 */
    private void awaitInProgressRecord(UUID projectId, String path) throws Exception {
        awaitInProgressRecord(projectId, "PUT", path);
    }

    /** 等待指定写方法的公共幂等抢占提交，供发布POST复用真实在途观察。 */
    private void awaitInProgressRecord(UUID projectId, String method, String path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection connection = fixtureOwnerConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT count(*) FROM sys_idempotency_record
                          WHERE project_id=? AND request_method=? AND request_path=? AND status='IN_PROGRESS'
                         """)) {
                statement.setObject(1, projectId);
                statement.setString(2, method);
                statement.setString(3, path);
                try (java.sql.ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) == 1) {
                        return;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("首次看板写请求未进入幂等IN_PROGRESS状态");
    }

    /** 一次HTTP测试使用的可信身份与项目归属。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {
    }

    /** 两次真实发布形成的回滚HTTP夹具，保存不可变目标回执以核对200正文。 */
    private record RollbackFixture(
            Fixture fixture,
            UUID dashboardId,
            UUID firstVersionId,
            JsonNode firstVersion,
            UUID secondVersionId) {
    }
}
