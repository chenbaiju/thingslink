package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.application.publication.DashboardHostQualificationPort;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 应用目录、历史版本、创建、改名与草稿HTTP边界的真实认证、项目隔离及JSON合同验收。
 *
 * <p>S12-1d1开放既有只读用例，S12-1d2补充幂等创建、改名和草稿保存，
 * S12-1e2b开放不可变历史版本列表与详情，S12-1e2c接入受控发布新版本。本类使用生产签名器、
 * 完整安全过滤链和真实PostgreSQL，避免Mock服务把项目令牌、RLS、原文字节或公共幂等
 * 映射错误隐藏在Controller成功响应之后。</p>
 */
@AutoConfigureMockMvc
@DisplayName("应用管理接口")
class ApplicationManagementApiTests extends AbstractIntegrationTest {

    /** JSON只负责读取HTTP响应；持久夹具仍由PostgreSQL解析真实jsonb。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 超过JavaScript安全整数的合法revision，证明浏览器合同没有退化为JSON number。 */
    private static final long LARGE_REVISION = 9_007_199_254_740_993L;

    /** 当前用例创建的项目；用例结束后按项目精确回收应用及其看板依赖。 */
    private final List<UUID> fixtureProjectIds = new ArrayList<>();

    /** 真实Spring MVC及安全过滤链入口。 */
    @Autowired
    private MockMvc mvc;
    /** 生产JWT签名器；测试只跳过登录交互，不绕过令牌验签或项目快照。 */
    @Autowired
    private TokenIssuer tokens;

    /** 正向发布仅替换尚未交付的生产HostDescriptor注册事实，领域事务与真实数据库保持生产实现。 */
    @MockitoBean(enforceOverride = true)
    private ManagedWebAppHostQualificationAdapter hostQualifications;

    /** 每例恢复已冻结的受管宿主资格；缺失描述符的60045由专门反例覆盖。 */
    @BeforeEach
    void qualifyApplicationPublicationForHttpTests() {
        when(hostQualifications.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));
    }

    /** 回收当前用例提交的应用与看板事实，避免共享数据库的后续全项目重置被新外键阻断。 */
    @AfterEach
    void clearPersistedApplicationFixtures() throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            DashboardApplicationFixtureCleaner.clearProjects(connection, fixtureProjectIds);
        } finally {
            fixtureProjectIds.clear();
        }
    }

    /** 未认证请求必须在进入应用Controller前拒绝。 */
    @Test
    @DisplayName("未认证请求统一返回401")
    void rejectsUnauthenticatedRequests() throws Exception {
        UUID projectId = Uuid7.generate();
        UUID applicationId = Uuid7.generate();

        for (String path : List.of(
                basePath(projectId),
                basePath(projectId) + "/" + applicationId,
                basePath(projectId) + "/" + applicationId + "/draft")) {
            MvcResult response = mvc.perform(get(path)).andReturn();
            assertThat(response.getResponse().getStatus()).as(path).isEqualTo(401);
            assertThat(response.getHandler()).as(path).isNull();
        }
        for (MockHttpServletRequestBuilder request : List.of(
                post(basePath(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "anonymous-application-create")
                        .content(createEnvelope("匿名应用", validContent("匿名草稿"))),
                patch(itemPath(projectId, applicationId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managementName\":\"匿名改名\"}"),
                put(draftPath(projectId, applicationId)).contentType(MediaType.APPLICATION_JSON)
                        .content(saveEnvelope("0", validContent("匿名草稿"))))) {
            MvcResult response = mvc.perform(request).andReturn();
            assertThat(response.getResponse().getStatus()).isEqualTo(401);
            assertThat(response.getHandler()).isNull();
        }
    }

    /** 四个Console项目角色都拥有应用读取能力，且三条路径返回同一真实聚合。 */
    @ParameterizedTest(name = "{0}可读取应用目录和草稿")
    @EnumSource(ProjectRole.class)
    void everyProjectRoleReadsCatalogItemAndDraft(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        Instant updatedAt = Instant.parse("2026-09-06T20:10:11.123456Z");
        JsonNode content = JSON.readTree("""
                {"formatVersion":"tc.application/v1","displayName":"冷链应用",\
                 "nested":{"labels":["冷库",2,true]}}""");
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "冷链应用", LARGE_REVISION, LARGE_REVISION, updatedAt, content, false);

        JsonNode page = ok(authenticatedGet(fixture, basePath(fixture.projectId())));
        assertThat(fields(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertCatalog(page.path("items").get(0), applicationId, "冷链应用", LARGE_REVISION);

        JsonNode item = ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "/" + applicationId));
        assertCatalog(item, applicationId, "冷链应用", LARGE_REVISION);

        JsonNode draft = ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "/" + applicationId + "/draft"));
        assertThat(fields(draft)).containsExactlyInAnyOrder("applicationId", "content", "revision", "updatedAt");
        assertThat(draft.path("applicationId").asString()).isEqualTo(applicationId.toString());
        assertThat(draft.path("revision").isString()).isTrue();
        assertThat(draft.path("revision").asString()).isEqualTo(Long.toString(LARGE_REVISION));
        assertThat(draft.path("content")).isEqualTo(content);
        assertThat(draft.path("updatedAt").asString()).endsWith("Z");
    }

    /** 四种项目角色都可读轻量历史与完整快照，Long字段不得退化为JSON number。 */
    @ParameterizedTest(name = "{0}可读应用历史版本")
    @EnumSource(ProjectRole.class)
    void everyProjectRoleReadsApplicationVersionHistory(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "版本应用", 0, 0, Instant.now(), draftContent("版本草稿"), false);
        JsonNode snapshot = versionSnapshot("角色-" + role.name());
        VersionFixture version = insertApplicationVersion(
                fixture, applicationId, LARGE_REVISION, LARGE_REVISION,
                snapshot, Instant.parse("2026-09-06T21:10:11.123456Z"));

        JsonNode page = ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), applicationId)));
        assertThat(fields(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertVersionSummary(page.path("items").get(0), version);

        JsonNode detail = ok(authenticatedGet(
                fixture, versionPath(fixture.projectId(), applicationId, version.id())));
        assertVersionDetail(detail, version);
    }

    /** ARCHIVED项目保留读权限，不得把发布历史错当作新写入拒绝。 */
    @Test
    @DisplayName("归档项目仍可读应用历史")
    void archivedProjectStillReadsApplicationVersionHistory() throws Exception {
        Fixture fixture = seed(ProjectRole.VIEWER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "归档应用", 1, 0, Instant.now(), draftContent("归档草稿"), false);
        VersionFixture version = insertApplicationVersion(
                fixture, applicationId, 1, 0, versionSnapshot("归档快照"), Instant.now());
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        assertVersionSummary(ok(authenticatedGet(
                fixture, versionsPath(fixture.projectId(), applicationId))).path("items").get(0), version);
        assertVersionDetail(ok(authenticatedGet(
                fixture, versionPath(fixture.projectId(), applicationId, version.id()))), version);
    }

    /** 可见但从未发布的应用必须返回空页，不得与60030不可见混同。 */
    @Test
    @DisplayName("从未发布的应用返回空历史页")
    void returnsEmptyHistoryForVisibleUnpublishedApplication() throws Exception {
        Fixture fixture = seed(ProjectRole.OPERATOR);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "未发布应用", 0, 0, Instant.now(), draftContent("未发布草稿"), false);

        JsonNode page = ok(authenticatedGet(fixture, versionsPath(fixture.projectId(), applicationId)));

        assertThat(fields(page)).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
    }

    /** 版本号倒序游标需稳定续页，默认50和显式1/200均必须由真实数据证明。 */
    @Test
    @DisplayName("应用历史按版本号倒序并守住分页边界")
    void pagesApplicationVersionsByDescendingVersionNumber() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "分页应用", 51, 0, Instant.now(), draftContent("分页草稿"), false);
        insertApplicationVersions(fixture, applicationId, 51);
        String path = versionsPath(fixture.projectId(), applicationId);

        JsonNode first = ok(authenticatedGet(fixture, path));
        assertThat(first.path("items")).hasSize(50);
        assertThat(first.path("items").get(0).path("versionNumber").asString()).isEqualTo("51");
        assertThat(first.path("items").get(49).path("versionNumber").asString()).isEqualTo("2");
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").isTextual()).isTrue();

        JsonNode second = ok(authenticatedGet(
                fixture, path + "?cursor=" + first.path("nextCursor").asString()));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("items").get(0).path("versionNumber").asString()).isEqualTo("1");
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("hasMore").asBoolean()).isFalse();

        assertThat(ok(authenticatedGet(fixture, path + "?limit=1")).path("items")).hasSize(1);
        assertThat(ok(authenticatedGet(fixture, path + "?limit=200")).path("items")).hasSize(51);
        for (String query : List.of("?limit=0", "?limit=201", "?limit=abc", "?cursor=not-a-cursor")) {
            error(authenticatedGet(fixture, path + query), 400, 10001);
        }
    }

    /** 项目、应用与精确版本的三层隐藏必须返回各自稳定业务码。 */
    @Test
    @DisplayName("应用历史区分50001、60030与60048")
    void classifiesHiddenProjectApplicationAndExactVersion() throws Exception {
        Fixture fixture = seed(ProjectRole.VIEWER);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "本地应用", 0, 0, Instant.now(), draftContent("本地草稿"), false);
        UUID siblingApplicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "同项目应用", 0, 0, Instant.now(), draftContent("同项目草稿"), false);
        VersionFixture siblingVersion = insertApplicationVersion(
                fixture, siblingApplicationId, 1, 0, versionSnapshot("同项目快照"), Instant.now());
        UUID deletedApplicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "已删应用", 1, 0, Instant.now(), draftContent("已删草稿"), false);
        VersionFixture deletedVersion = insertApplicationVersion(
                fixture, deletedApplicationId, 1, 0, versionSnapshot("已删快照"), Instant.now());
        softDeleteApplication(deletedApplicationId);
        UUID foreignApplicationId = insertApplication(
                foreign.projectId(), foreign.tenantId(), foreign.accountId(),
                "外部应用", 1, 0, Instant.now(), draftContent("外部草稿"), false);
        VersionFixture foreignVersion = insertApplicationVersion(
                foreign, foreignApplicationId, 1, 0, versionSnapshot("外部快照"), Instant.now());

        error(authenticatedGet(fixture, versionsPath(foreign.projectId(), foreignApplicationId)), 404, 50001);
        error(authenticatedGet(fixture,
                versionPath(foreign.projectId(), foreignApplicationId, foreignVersion.id())), 404, 50001);

        error(authenticatedGet(fixture, versionsPath(fixture.projectId(), Uuid7.generate())), 404, 60030);
        error(authenticatedGet(fixture, versionsPath(fixture.projectId(), foreignApplicationId)), 404, 60030);
        error(authenticatedGet(fixture, versionsPath(fixture.projectId(), deletedApplicationId)), 404, 60030);
        error(authenticatedGet(fixture,
                versionPath(fixture.projectId(), deletedApplicationId, deletedVersion.id())), 404, 60030);

        error(authenticatedGet(fixture,
                versionPath(fixture.projectId(), applicationId, Uuid7.generate())), 404, 60048);
        error(authenticatedGet(fixture,
                versionPath(fixture.projectId(), applicationId, siblingVersion.id())), 404, 60048);
        error(authenticatedGet(fixture,
                versionPath(fixture.projectId(), applicationId, foreignVersion.id())), 404, 60048);
    }

    /** OWNER与ADMIN可发布合格草稿，HTTP版本详情、Location与数据库原子事实必须指向同一版本。 */
    @ParameterizedTest(name = "{0}可发布应用新版本")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色发布应用并返回完整类型化版本")
    void managementRolesPublishApplicationVersion(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        PublishableApplication publishable = insertPublishableApplication(fixture, "发布应用", 0, 0);
        String path = versionsPath(fixture.projectId(), publishable.applicationId());

        MvcResult result = write(fixture, post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0")));
        JsonNode response = created(result);
        UUID versionId = UUID.fromString(response.path("id").asString());
        VersionFixture persisted = applicationVersion(publishable.applicationId(), versionId);

        assertVersionDetail(response, persisted);
        assertPublishedSnapshot(response.path("snapshot"), publishable);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(versionPath(fixture.projectId(), publishable.applicationId(), versionId));
        assertThat(applicationPublicationState(publishable.applicationId()))
                .isEqualTo("1:" + versionId + ":1:1");
        assertThat(applicationPublicationAuditCount(publishable.applicationId())).isOne();

        JsonNode catalog = ok(authenticatedGet(fixture, itemPath(fixture.projectId(), publishable.applicationId())));
        assertThat(catalog.path("publicationRevision").asString()).isEqualTo("1");
        assertThat(catalog.path("currentVersionId").asString()).isEqualTo(versionId.toString());
        assertVersionDetail(ok(authenticatedGet(
                fixture, versionPath(fixture.projectId(), publishable.applicationId(), versionId))), persisted);
    }

    /** 只读角色不能发布；HTTP首层授权必须在解析候选或写入版本前稳定返回60031。 */
    @ParameterizedTest(name = "{0}不能发布应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色发布应用返回60031")
    void readOnlyRolesCannotPublishApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "只读发布", 0, 0, Instant.now(), JSON.readTree(validContent("只读发布")), false);

        error(write(fixture, post(versionsPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 403, 60031);

        assertThat(applicationPublicationState(applicationId)).isEqualTo("0:null:0:0");
    }

    /** ARCHIVED项目即使调用方仍是OWNER也必须按项目生命周期拒绝发布且保持零版本。 */
    @Test
    @DisplayName("归档项目发布应用返回50017")
    void archivedProjectRejectsApplicationPublication() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "归档发布", 0, 0, Instant.now(), JSON.readTree(validContent("归档发布")), false);
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        error(write(fixture, post(versionsPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 403, 50017);

        assertThat(applicationPublicationState(applicationId)).isEqualTo("0:null:0:0");
    }

    /** 严格外层信封错误属于10002，规范字符串进入领域后才按候选或revision语义返回60043。 */
    @Test
    @DisplayName("应用发布区分外层信封与候选语义错误")
    void publicationDistinguishesMalformedEnvelopeAndInvalidCandidate() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication publishable = insertPublishableApplication(fixture, "信封发布", 0, 0);
        String path = versionsPath(fixture.projectId(), publishable.applicationId());

        for (String body : List.of(
                "{}",
                "{\"expectedDraftRevision\":0,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":null}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\",\"unknown\":true}",
                "{\"expectedDraftRevision\":\"0\",\"expectedDraftRevision\":\"0\","
                        + "\"expectedPublicationRevision\":\"0\"}")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10002);
        }
        for (String invalid : List.of("", " 0", "-1", "00", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publishEnvelope(invalid, "0"))), 400, 60043);
        }

        UUID emptyApplication = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "空应用", 0, 0, Instant.now(), JSON.readTree(validContent("空应用")), false);
        error(write(fixture, post(versionsPath(fixture.projectId(), emptyApplication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 400, 60043);

        assertThat(applicationPublicationState(publishable.applicationId())).isEqualTo("0:null:0:0");
        assertThat(applicationPublicationState(emptyApplication)).isEqualTo("0:null:0:0");
    }

    /** 双轴任一旧值及Long耗尽都属于发布状态冲突60044，不能留下版本或审计。 */
    @Test
    @DisplayName("应用发布区分双轴陈旧与Long耗尽")
    void publicationRejectsStaleRevisionsAndLongExhaustion() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication stale = insertPublishableApplication(fixture, "陈旧发布", 4, 7);
        String stalePath = versionsPath(fixture.projectId(), stale.applicationId());

        error(write(fixture, post(stalePath).contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("6", "4"))), 409, 60044);
        error(write(fixture, post(stalePath).contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("7", "3"))), 409, 60044);

        PublishableApplication exhausted = insertPublishableApplication(
                fixture, "耗尽发布", Long.MAX_VALUE, 0);
        error(write(fixture, post(versionsPath(fixture.projectId(), exhausted.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", Long.toString(Long.MAX_VALUE)))), 409, 60044);

        assertThat(applicationPublicationState(stale.applicationId())).isEqualTo("4:null:0:0");
        assertThat(applicationPublicationState(exhausted.applicationId()))
                .isEqualTo(Long.MAX_VALUE + ":null:0:0");
    }

    /** 生产共享HostDescriptor尚未登记时继续503失败关闭，HTTP开放不能伪装为可发布。 */
    @Test
    @DisplayName("应用发布在宿主资格不可用时返回60045")
    void publicationFailsClosedWhenHostQualificationIsUnavailable() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication publishable = insertPublishableApplication(fixture, "资格缺失", 0, 0);
        when(hostQualifications.current()).thenReturn(Optional.empty());

        error(write(fixture, post(versionsPath(fixture.projectId(), publishable.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))), 503, 60045);

        assertThat(applicationPublicationState(publishable.applicationId())).isEqualTo("0:null:0:0");
        assertThat(applicationPublicationAuditCount(publishable.applicationId())).isZero();
    }

    /** 可选公共幂等层仅保留完成墓碑：首次写一次，同正文10014，异正文10009。 */
    @Test
    @DisplayName("应用发布使用可选公共幂等完成墓碑")
    void publicationUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication publishable = insertPublishableApplication(fixture, "发布幂等", 0, 0);
        String path = versionsPath(fixture.projectId(), publishable.applicationId());
        String key = "application-publish-once";
        String body = publishEnvelope("0", "0");

        JsonNode published = created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(publishEnvelope("0", "1"))), 409, 10009);

        assertThat(applicationPublicationState(publishable.applicationId()))
                .isEqualTo("1:" + published.path("id").asString() + ":1:1");
        assertThat(applicationPublicationAuditCount(publishable.applicationId())).isOne();
    }

    /** 首请求等待真实应用目录行锁时，同键重试必须10010且不能进入第二次发布事务。 */
    @Test
    @DisplayName("应用发布对在途同键请求返回10010")
    void publicationReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication publishable = insertPublishableApplication(fixture, "发布在途", 0, 0);
        String path = versionsPath(fixture.projectId(), publishable.applicationId());
        String body = publishEnvelope("0", "0");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM app_application WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, publishable.applicationId());
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-publish-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);

            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-publish-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();
            created(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(applicationPublicationState(publishable.applicationId())).startsWith("1:").endsWith(":1:1");
        assertThat(applicationPublicationAuditCount(publishable.applicationId())).isOne();
    }

    /** OWNER与ADMIN回滚只切当前指针和发布revision，目标版本正文与全部历史事实保持不变。 */
    @ParameterizedTest(name = "{0}可回滚应用历史版本")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色回滚应用并返回目标完整版本")
    void managementRolesRollbackApplicationVersion(ProjectRole role) throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(seed(role), "回滚应用");
        List<String> immutableHistory = applicationHistoryFacts(rollback.application().applicationId());

        MvcResult result = write(rollback.fixture(), post(rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2")));
        JsonNode response = ok(result);

        assertThat(response).isEqualTo(rollback.firstVersion());
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2:2");
        assertThat(applicationHistoryFacts(rollback.application().applicationId())).isEqualTo(immutableHistory);
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isOne();
    }

    /** 只读角色不能回滚；首层application:manage守卫必须稳定优先于信封和目标解释。 */
    @ParameterizedTest(name = "{0}不能回滚应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色回滚应用返回60031")
    void readOnlyRolesCannotRollbackApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "只读回滚", 0, 0, Instant.now(), JSON.readTree(validContent("只读回滚")), false);

        error(write(fixture, post(rollbackPath(fixture.projectId(), applicationId, Uuid7.generate()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 60031);
    }

    /** ARCHIVED项目保留历史GET但拒绝回滚写，且项目生命周期错误优先于目标可见性。 */
    @Test
    @DisplayName("归档项目回滚应用返回50017")
    void archivedProjectRejectsApplicationRollback() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "归档回滚", 0, 0, Instant.now(), JSON.readTree(validContent("归档回滚")), false);
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        error(write(fixture, post(rollbackPath(fixture.projectId(), applicationId, Uuid7.generate()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 50017);
    }

    /** 单revision严格信封错误沿10002；规范字符串语法非法或历史候选当前不可运行沿60043。 */
    @Test
    @DisplayName("应用回滚区分外层信封、revision和候选错误")
    void rollbackDistinguishesMalformedEnvelopeRevisionAndCandidate() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "回滚边界");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId());

        for (String body : List.of(
                "{}",
                "{\"expectedPublicationRevision\":2}",
                "{\"expectedPublicationRevision\":null}",
                "{\"expectedPublicationRevision\":\"2\",\"unknown\":true}",
                "{\"expectedPublicationRevision\":\"2\",\"expectedPublicationRevision\":\"2\"}")) {
            error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON).content(body)),
                    400, 10002);
        }
        for (String invalid : List.of("", " 2", "-1", "02", "9223372036854775808")) {
            error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60043);
        }

        softDeleteDashboard(rollback.application().dashboard().dashboardId());
        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 400, 60043);
        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2:2");
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isZero();
    }

    /** 旧revision、Long耗尽和目标已是current统一60044，失败不能改写指针或历史。 */
    @Test
    @DisplayName("应用回滚拒绝陈旧状态、Long耗尽和当前目标")
    void rollbackRejectsStaleExhaustedAndCurrentVersion() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "回滚冲突");
        String firstPath = rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId());
        String currentPath = rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.secondVersionId());

        error(write(rollback.fixture(), post(firstPath).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 409, 60044);
        error(write(rollback.fixture(), post(rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), Uuid7.generate()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))), 409, 60044);
        error(write(rollback.fixture(), post(currentPath).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 409, 60044);
        setApplicationPublicationRevision(rollback.application().applicationId(), Long.MAX_VALUE);
        error(write(rollback.fixture(), post(firstPath).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60044);

        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo(Long.MAX_VALUE + ":" + rollback.secondVersionId() + ":2:2");
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isZero();
    }

    /** 目标不存在、属于同项目其他应用或其他项目时统一60046，不能暴露真实归属。 */
    @Test
    @DisplayName("应用回滚隐藏缺失与跨范围目标为60046")
    void rollbackHidesMissingCrossApplicationAndCrossProjectTargets() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "目标隐藏");
        Fixture fixture = rollback.fixture();
        UUID siblingApplication = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "同项目其他应用", 0, 0, Instant.now(), draftContent("同项目其他应用"), false);
        VersionFixture siblingVersion = insertApplicationVersion(
                fixture, siblingApplication, 1, 0, versionSnapshot("同项目其他版本"), Instant.now());
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignApplication = insertApplication(
                foreign.projectId(), foreign.tenantId(), foreign.accountId(),
                "其他项目应用", 0, 0, Instant.now(), draftContent("其他项目应用"), false);
        VersionFixture foreignVersion = insertApplicationVersion(
                foreign, foreignApplication, 1, 0, versionSnapshot("其他项目版本"), Instant.now());

        for (UUID hidden : List.of(Uuid7.generate(), siblingVersion.id(), foreignVersion.id())) {
            error(write(fixture, post(rollbackPath(
                    fixture.projectId(), rollback.application().applicationId(), hidden))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("2"))), 404, 60046);
        }
        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2:2");
    }

    /** 应用目录缺失、跨项目或已软删统一60030，不能因目标版本路径泄露目录历史。 */
    @Test
    @DisplayName("应用回滚隐藏缺失、跨项目和软删应用为60030")
    void rollbackHidesMissingCrossProjectAndDeletedApplications() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID deletedApplication = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "已删回滚应用", 0, 0, Instant.now(), draftContent("已删回滚应用"), true);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignApplication = insertApplication(
                foreign.projectId(), foreign.tenantId(), foreign.accountId(),
                "跨项目回滚应用", 0, 0, Instant.now(), draftContent("跨项目回滚应用"), false);

        for (UUID hidden : List.of(Uuid7.generate(), foreignApplication, deletedApplication)) {
            error(write(fixture, post(rollbackPath(fixture.projectId(), hidden, Uuid7.generate()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("0"))), 404, 60030);
        }
    }

    /** 回滚重建历史候选仍必须读取当前共享HostDescriptor；缺失时60045且不切指针。 */
    @Test
    @DisplayName("应用回滚在宿主资格不可用时返回60045")
    void rollbackFailsClosedWhenHostQualificationIsUnavailable() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "回滚资格缺失");
        when(hostQualifications.current()).thenReturn(Optional.empty());

        error(write(rollback.fixture(), post(rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 503, 60045);

        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("2:" + rollback.secondVersionId() + ":2:2");
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isZero();
    }

    /** 公共完成墓碑不重放200目标正文：首次只回滚一次，同正文10014，异正文10009。 */
    @Test
    @DisplayName("应用回滚使用可选公共幂等完成墓碑")
    void rollbackUsesOptionalCommonIdempotencyTombstone() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "回滚幂等");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId());
        String body = publicationRevisionEnvelope("2");
        String key = "application-rollback-once";

        ok(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(publicationRevisionEnvelope("3"))), 409, 10009);

        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2:2");
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isOne();
    }

    /** 首次回滚等待真实应用目录锁时，同键重试必须10010且只能提交一次指针变更。 */
    @Test
    @DisplayName("应用回滚对在途同键请求返回10010")
    void rollbackReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        ApplicationRollbackFixture rollback = prepareApplicationRollbackFixture(
                seed(ProjectRole.OWNER), "回滚在途");
        String path = rollbackPath(
                rollback.fixture().projectId(), rollback.application().applicationId(), rollback.firstVersionId());
        String body = publicationRevisionEnvelope("2");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM app_application WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, rollback.application().applicationId());
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(rollback.fixture(), post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-rollback-in-progress")
                    .content(body)));
            awaitInProgressRecord(rollback.fixture().projectId(), "POST", path);
            error(write(rollback.fixture(), post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-rollback-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();
            ok(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(applicationPublicationState(rollback.application().applicationId()))
                .isEqualTo("3:" + rollback.firstVersionId() + ":2:2");
        assertThat(applicationRollbackAuditCount(rollback.application().applicationId())).isOne();
    }

    /** OWNER与ADMIN可无幂等键撤回；即使HostDescriptor缺失也只清指针并保留草稿、版本和关系。 */
    @ParameterizedTest(name = "{0}可撤回应用当前发布")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色撤回应用并返回空204")
    void managementRolesWithdrawApplicationPublication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        PublishableApplication application = insertPublishableApplication(fixture, "撤回应用", 0, 0);
        JsonNode version = created(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        UUID versionId = UUID.fromString(version.path("id").asString());
        List<String> immutableHistory = applicationHistoryFacts(application.applicationId());
        String immutableDraft = applicationDraftFact(application.applicationId());
        when(hostQualifications.current()).thenReturn(Optional.empty());

        MvcResult result = write(fixture, post(withdrawPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1")));

        noContent(result);
        assertThat(applicationPublicationState(application.applicationId()))
                .isEqualTo("2:null:1:1");
        assertThat(applicationHistoryFacts(application.applicationId())).isEqualTo(immutableHistory);
        assertThat(applicationDraftFact(application.applicationId())).isEqualTo(immutableDraft);
        assertThat(applicationPublicationAuditCount(application.applicationId())).isOne();
        assertThat(applicationWithdrawalAuditCount(application.applicationId())).isOne();
        assertThat(immutableHistory.getFirst()).startsWith(versionId + ":1:");
    }

    /** 只读角色不能撤回；application:manage必须先于信封和当前指针解释。 */
    @ParameterizedTest(name = "{0}不能撤回应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色撤回应用返回60031")
    void readOnlyRolesCannotWithdrawApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "只读撤回应用", 0, 0, Instant.now(), draftContent("只读撤回应用"), false);

        error(write(fixture, post(withdrawPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 60031);
    }

    /** ARCHIVED先按项目写许可拒绝，缺失、跨项目或软删应用统一60030隐藏。 */
    @Test
    @DisplayName("应用撤回保持项目生命周期与目录隐藏边界")
    void withdrawalPreservesLifecycleAndApplicationVisibilityErrors() throws Exception {
        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedApplication = insertApplication(
                archived.projectId(), archived.tenantId(), archived.accountId(),
                "归档撤回应用", 0, 0, Instant.now(), draftContent("归档撤回应用"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(withdrawPath(archived.projectId(), archivedApplication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 50017);

        Fixture fixture = seed(ProjectRole.OWNER);
        UUID deletedApplication = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "已删撤回应用", 0, 0, Instant.now(), draftContent("已删撤回应用"), true);
        Fixture foreign = seed(ProjectRole.OWNER);
        UUID foreignApplication = insertApplication(
                foreign.projectId(), foreign.tenantId(), foreign.accountId(),
                "跨项目撤回应用", 0, 0, Instant.now(), draftContent("跨项目撤回应用"), false);
        for (UUID hidden : List.of(Uuid7.generate(), deletedApplication, foreignApplication)) {
            error(write(fixture, post(withdrawPath(fixture.projectId(), hidden))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope("0"))), 404, 60030);
        }
    }

    /** 外层严格错误沿10002；revision语法沿60043，旧值、耗尽和空指针沿60044。 */
    @Test
    @DisplayName("应用撤回区分信封、revision和发布冲突")
    void withdrawalDistinguishesEnvelopeRevisionAndPublicationConflicts() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication application = insertPublishableApplication(fixture, "撤回冲突", 0, 0);
        created(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        String path = withdrawPath(fixture.projectId(), application.applicationId());

        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPublicationRevision\":\"1\",\"unknown\":true}")), 400, 10002);
        for (String invalid : List.of("", " 1", "-1", "01", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60043);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 409, 60044);

        setApplicationPublicationRevision(application.applicationId(), Long.MAX_VALUE);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60044);
        setApplicationPublicationRevision(application.applicationId(), 1);

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 409, 60044);
        assertThat(applicationPublicationState(application.applicationId())).isEqualTo("2:null:1:1");
        assertThat(applicationWithdrawalAuditCount(application.applicationId())).isOne();
    }

    /** 公共完成墓碑不重放204：首次只撤回一次，同正文10014，异正文10009。 */
    @Test
    @DisplayName("应用撤回使用可选公共幂等完成墓碑")
    void withdrawalUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication application = insertPublishableApplication(fixture, "撤回幂等", 0, 0);
        created(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        String path = withdrawPath(fixture.projectId(), application.applicationId());
        String body = publicationRevisionEnvelope("1");
        String key = "application-withdraw-once";

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(publicationRevisionEnvelope("2"))), 409, 10009);

        assertThat(applicationPublicationState(application.applicationId())).isEqualTo("2:null:1:1");
        assertThat(applicationWithdrawalAuditCount(application.applicationId())).isOne();
    }

    /** 首次撤回等待真实应用目录锁时，同键重试必须10010且只提交一次状态变化。 */
    @Test
    @DisplayName("应用撤回对在途同键请求返回10010")
    void withdrawalReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication application = insertPublishableApplication(fixture, "撤回在途", 0, 0);
        created(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        String path = withdrawPath(fixture.projectId(), application.applicationId());
        String body = publicationRevisionEnvelope("1");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM app_application WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, application.applicationId());
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-withdraw-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-withdraw-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();
            noContent(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(applicationPublicationState(application.applicationId())).isEqualTo("2:null:1:1");
        assertThat(applicationWithdrawalAuditCount(application.applicationId())).isOne();
    }

    /** OWNER与ADMIN可无幂等键软删三种发布状态；Host缺失不阻止删除且全部子事实原样保留。 */
    @ParameterizedTest(name = "{0}可软删各发布状态应用")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色软删各发布状态应用并返回空204")
    void managementRolesSoftDeleteEveryApplicationPublicationState(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        String creationKey = "application-soft-delete-created-" + role.name().toLowerCase();
        JsonNode createdApplication = created(write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", creationKey)
                .content(createEnvelope("删除从未发布", validContent("删除从未发布")))));
        UUID unpublished = UUID.fromString(createdApplication.path("id").asString());

        PublishableApplication publishedApplication = insertPublishableApplication(fixture, "删除已发布", 0, 0);
        JsonNode publishedVersion = created(write(fixture, post(versionsPath(
                fixture.projectId(), publishedApplication.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        PublishableApplication withdrawnApplication = insertPublishableApplication(fixture, "删除已撤回", 0, 0);
        created(write(fixture, post(versionsPath(fixture.projectId(), withdrawnApplication.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        noContent(write(fixture, post(withdrawPath(fixture.projectId(), withdrawnApplication.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));

        String unpublishedFacts = applicationRetentionFacts(unpublished);
        String publishedFacts = applicationRetentionFacts(publishedApplication.applicationId());
        String withdrawnFacts = applicationRetentionFacts(withdrawnApplication.applicationId());
        when(hostQualifications.current()).thenReturn(Optional.empty());

        noContent(write(fixture, post(softDeletePath(fixture.projectId(), unpublished))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))));
        noContent(write(fixture, post(softDeletePath(fixture.projectId(), publishedApplication.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));
        noContent(write(fixture, post(softDeletePath(fixture.projectId(), withdrawnApplication.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))));

        assertThat(applicationPublicationState(unpublished)).isEqualTo("1:null:0:0");
        assertThat(applicationPublicationState(publishedApplication.applicationId()))
                .isEqualTo("2:null:1:1");
        assertThat(applicationPublicationState(withdrawnApplication.applicationId()))
                .isEqualTo("3:null:1:1");
        assertThat(applicationRetentionFacts(unpublished)).isEqualTo(unpublishedFacts).isEqualTo("1:1:0:0:1");
        assertThat(applicationRetentionFacts(publishedApplication.applicationId())).isEqualTo(publishedFacts);
        assertThat(applicationRetentionFacts(withdrawnApplication.applicationId())).isEqualTo(withdrawnFacts);
        assertThat(isApplicationDeleted(unpublished)).isTrue();
        assertThat(isApplicationDeleted(publishedApplication.applicationId())).isTrue();
        assertThat(isApplicationDeleted(withdrawnApplication.applicationId())).isTrue();
        assertThat(applicationDeletionAuditCount(unpublished)).isOne();
        assertThat(applicationDeletionAuditCount(publishedApplication.applicationId())).isOne();
        assertThat(applicationDeletionAuditCount(withdrawnApplication.applicationId())).isOne();
        assertThat(publishedFacts).contains(":1:1:0");
        assertThat(withdrawnFacts).contains(":1:1:0");
        assertThat(publishedVersion.path("id").asString()).isNotBlank();

        error(write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", creationKey)
                .content(createEnvelope("删除从未发布", validContent("删除从未发布")))), 409, 10014);
        assertThat(applicationRetentionFacts(unpublished)).isEqualTo("1:1:0:0:1");
    }

    /** 软删后的目录、草稿、历史和全部发布写入口永久统一60030，同时底层历史仍保留。 */
    @Test
    @DisplayName("软删应用永久隐藏全部管理与发布生命周期入口")
    void softDeletedApplicationRemainsHiddenAcrossAllManagementEndpoints() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        PublishableApplication application = insertPublishableApplication(fixture, "永久隐藏", 0, 0);
        JsonNode version = created(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        UUID versionId = UUID.fromString(version.path("id").asString());
        String retentionFacts = applicationRetentionFacts(application.applicationId());
        noContent(write(fixture, post(softDeletePath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("1"))));

        for (String path : List.of(
                itemPath(fixture.projectId(), application.applicationId()),
                draftPath(fixture.projectId(), application.applicationId()),
                versionsPath(fixture.projectId(), application.applicationId()),
                versionPath(fixture.projectId(), application.applicationId(), versionId))) {
            error(authenticatedGet(fixture, path), 404, 60030);
        }
        error(write(fixture, patch(itemPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"删除后改名\"}")), 404, 60030);
        error(write(fixture, put(draftPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent("删除后草稿")))), 404, 60030);
        error(write(fixture, post(versionsPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "2"))), 404, 60030);
        error(write(fixture, post(rollbackPath(fixture.projectId(), application.applicationId(), versionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 404, 60030);
        error(write(fixture, post(withdrawPath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 404, 60030);
        error(write(fixture, post(softDeletePath(fixture.projectId(), application.applicationId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("2"))), 404, 60030);

        assertThat(applicationRetentionFacts(application.applicationId())).isEqualTo(retentionFacts);
        assertThat(applicationPublicationState(application.applicationId())).isEqualTo("2:null:1:1");
        assertThat(applicationDeletionAuditCount(application.applicationId())).isOne();
    }

    /** 只读角色不能软删；application:manage必须先于信封与发布状态解释。 */
    @ParameterizedTest(name = "{0}不能软删应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色软删应用返回60031")
    void readOnlyRolesCannotSoftDeleteApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "只读软删应用", 0, 0, Instant.now(), draftContent("只读软删应用"), false);

        error(write(fixture, post(softDeletePath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 60031);
        assertThat(isApplicationDeleted(applicationId)).isFalse();
        assertThat(applicationDeletionAuditCount(applicationId)).isZero();
    }

    /** ARCHIVED按项目写许可拒绝；严格信封、revision语法、陈旧状态与Long耗尽保持分层错误。 */
    @Test
    @DisplayName("应用软删区分生命周期、信封、revision和发布冲突")
    void softDeleteDistinguishesLifecycleEnvelopeRevisionAndPublicationConflicts() throws Exception {
        Fixture archived = seed(ProjectRole.OWNER);
        UUID archivedApplication = insertApplication(
                archived.projectId(), archived.tenantId(), archived.accountId(),
                "归档软删应用", 0, 0, Instant.now(), draftContent("归档软删应用"), false);
        setProjectStatus(archived.projectId(), "ARCHIVED");
        error(write(archived, post(softDeletePath(archived.projectId(), archivedApplication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 403, 50017);

        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "软删冲突应用", 1, 0, Instant.now(), draftContent("软删冲突应用"), false);
        String path = softDeletePath(fixture.projectId(), applicationId);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPublicationRevision\":\"1\",\"unknown\":true}")), 400, 10002);
        for (String invalid : List.of("", " 1", "-1", "01", "9223372036854775808")) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(publicationRevisionEnvelope(invalid))), 400, 60043);
        }
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope("0"))), 409, 60044);
        setApplicationPublicationRevision(applicationId, Long.MAX_VALUE);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(publicationRevisionEnvelope(Long.toString(Long.MAX_VALUE)))), 409, 60044);

        assertThat(isApplicationDeleted(applicationId)).isFalse();
        assertThat(applicationDeletionAuditCount(applicationId)).isZero();
    }

    /** 公共完成墓碑不重放204：同正文10014、异正文10009且创建映射和历史都不清理。 */
    @Test
    @DisplayName("应用软删使用可选公共幂等完成墓碑")
    void softDeleteUsesOptionalCommonIdempotencyTombstone() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "软删幂等应用", 0, 0, Instant.now(), draftContent("软删幂等应用"), false);
        String path = softDeletePath(fixture.projectId(), applicationId);
        String body = publicationRevisionEnvelope("0");
        String key = "application-soft-delete-once";

        noContent(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(publicationRevisionEnvelope("1"))), 409, 10009);

        assertThat(applicationPublicationState(applicationId)).isEqualTo("1:null:0:0");
        assertThat(applicationDeletionAuditCount(applicationId)).isOne();
    }

    /** 首次软删等待真实应用目录锁时，同键重试必须10010且只提交一次删除事实。 */
    @Test
    @DisplayName("应用软删对在途同键请求返回10010")
    void softDeleteReportsInProgressForConcurrentIdempotentRequest() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "软删在途应用", 0, 0, Instant.now(), draftContent("软删在途应用"), false);
        String path = softDeletePath(fixture.projectId(), applicationId);
        String body = publicationRevisionEnvelope("0");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM app_application WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, applicationId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-soft-delete-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), "POST", path);
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-soft-delete-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();
            noContent(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(applicationPublicationState(applicationId)).isEqualTo("1:null:0:0");
        assertThat(applicationDeletionAuditCount(applicationId)).isOne();
    }

    /** 项目不存在边界与应用不存在边界必须分层，后者还须隐藏跨项目和软删状态。 */
    @Test
    @DisplayName("非成员、缺失、跨项目和软删保持稳定隐藏分类")
    void hidesProjectsAndApplicationsWithoutLeakingTheirState() throws Exception {
        Fixture fixture = seed(ProjectRole.VIEWER);
        Fixture foreign = seed(ProjectRole.OWNER);
        JsonNode content = draftContent("隔离应用");
        UUID local = insertApplication(fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "已删除应用", 0, 0, Instant.now(), content, true);
        UUID foreignApplication = insertApplication(
                foreign.projectId(), foreign.tenantId(), foreign.accountId(),
                "邻居应用", 0, 0, Instant.now(), content, false);

        error(authenticatedGet(fixture, basePath(foreign.projectId())), 404, 50001);
        for (UUID hidden : List.of(Uuid7.generate(), foreignApplication, local)) {
            error(authenticatedGet(fixture, basePath(fixture.projectId()) + "/" + hidden), 404, 60030);
            error(authenticatedGet(fixture, basePath(fixture.projectId()) + "/" + hidden + "/draft"), 404, 60030);
        }
    }

    /** 目录按更新时间倒序、同时间ID正序翻页，游标不得重复或跳过同时间行。 */
    @Test
    @DisplayName("目录分页保持稳定顺序和不透明下一页游标")
    void pagesByUpdatedTimeAndStableApplicationId() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        Instant older = Instant.parse("2026-09-06T20:00:00.123456Z");
        Instant newer = older.plusSeconds(1);
        List<UUID> sameTime = new ArrayList<>(List.of(Uuid7.generate(), Uuid7.generate()));
        sameTime.sort((left, right) -> left.toString().compareTo(right.toString()));
        UUID newest = Uuid7.generate();
        insertApplication(fixture, newest, "最新", newer);
        insertApplication(fixture, sameTime.get(1), "同刻后项", older);
        insertApplication(fixture, sameTime.get(0), "同刻前项", older);

        JsonNode first = ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "?limit=2"));
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").isString()).isTrue();
        assertThat(first.path("items").get(0).path("id").asString()).isEqualTo(newest.toString());
        assertThat(first.path("items").get(1).path("id").asString()).isEqualTo(sameTime.get(0).toString());

        String cursor = first.path("nextCursor").asString();
        JsonNode second = ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "?limit=2&cursor=" + cursor));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(sameTime.get(1).toString());
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    /** 页大小和游标由HTTP参数绑定与仓储共同拒绝，不能让畸形位置退化成首页。 */
    @Test
    @DisplayName("页大小边界和畸形游标返回通用参数错误")
    void rejectsInvalidPageBoundsAndMalformedCursor() throws Exception {
        Fixture fixture = seed(ProjectRole.OPERATOR);
        insertApplication(fixture, Uuid7.generate(), "参数应用", Instant.now());

        assertThat(ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "?limit=1")).path("items")).hasSize(1);
        assertThat(ok(authenticatedGet(fixture, basePath(fixture.projectId()) + "?limit=200")).path("items")).hasSize(1);
        for (String query : List.of("?limit=0", "?limit=201", "?limit=abc", "?cursor=not-a-cursor")) {
            error(authenticatedGet(fixture, basePath(fixture.projectId()) + query), 400, 10001);
        }
    }

    /** OWNER与ADMIN可修改目录名称并以字符串revision完整替换草稿。 */
    @ParameterizedTest(name = "{0}可修改应用名称和草稿")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色可改名并保存草稿")
    void managementRolesRenameAndSaveDraft(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "旧名称", 0, 0, Instant.now(), draftContent("旧草稿"), false);

        JsonNode renamed = ok(write(fixture, patch(itemPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\" 新名称 \"}")));
        assertThat(renamed.path("managementName").asString()).isEqualTo(" 新名称 ");

        String content = validContent("新草稿");
        JsonNode saved = ok(write(fixture, put(draftPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"0\",\"content\":" + content + "}")));
        assertThat(fields(saved)).containsExactlyInAnyOrder("applicationId", "content", "revision", "updatedAt");
        assertThat(saved.path("applicationId").asString()).isEqualTo(applicationId.toString());
        assertThat(saved.path("revision").isString()).isTrue();
        assertThat(saved.path("revision").asString()).isEqualTo("1");
        assertThat(saved.path("content")).isEqualTo(JSON.readTree(content));
    }

    /** OWNER与ADMIN首次创建和同语义重放均须恢复唯一不可变身份，且不重复任何持久副作用。 */
    @ParameterizedTest(name = "{0}可幂等创建应用")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "ADMIN"})
    @DisplayName("管理角色创建应用并稳定重放不可变回执")
    void managementRolesCreateApplicationWithStableReceipt(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        String key = "application-create-stable-" + role.name().toLowerCase();
        String content = validContent("初始草稿");
        MvcResult firstResult = write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(createEnvelope(" 初始应用 ", content)));
        JsonNode first = created(firstResult);
        UUID applicationId = UUID.fromString(first.path("id").asString());

        assertThat(fields(first)).containsExactlyInAnyOrder("id", "appKey", "createdAt");
        assertThat(first.path("appKey").asString()).matches("app_[0-9a-f]{32}");
        assertThat(first.path("createdAt").asString()).endsWith("Z");
        assertThat(firstResult.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(itemPath(fixture.projectId(), applicationId));
        assertThat(applicationName(applicationId)).isEqualTo(" 初始应用 ");
        assertThat(draftRevision(applicationId)).isZero();

        // 创建回执只含不可变身份；先改变目录名称，再证明旧创建请求仍恢复原始回执。
        ok(write(fixture, patch(itemPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"创建后改名\"}")));
        assertThat(applicationName(applicationId)).isEqualTo("创建后改名");

        // 外层字段顺序与空白没有业务含义；content对象自身字节保持不变，故应恢复相同身份。
        MvcResult replayResult = write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("  { \"content\" : " + content
                        + " , \"managementName\" : \" 初始应用 \" }  "));
        JsonNode replay = created(replayResult);

        assertThat(replay).isEqualTo(first);
        assertThat(replayResult.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(itemPath(fixture.projectId(), applicationId));
        assertProjectCreationFacts(fixture, 1);
    }

    /** 同一账号和项目中的幂等键必须绑定名称及content，任一变化都不得创建第二个身份。 */
    @Test
    @DisplayName("应用创建同键异名称或异content返回10009")
    void rejectsChangedApplicationCreationRequestForSameKey() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());
        String key = "application-create-conflict";
        String content = validContent("原始草稿");

        created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(createEnvelope("原始名称", content))));
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(createEnvelope("变化名称", content))), 409, 10009);
        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(createEnvelope("原始名称", validContent("变化草稿")))), 409, 10009);

        assertProjectCreationFacts(fixture, 1);
    }

    /** 缺失、空白和超长键必须在首次持久写前失败，绝不能退化为普通非幂等创建。 */
    @Test
    @DisplayName("应用创建拒绝无效Idempotency-Key")
    void rejectsMissingBlankAndOversizedApplicationCreationKeys() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());
        String body = createEnvelope("键边界应用", validContent("键边界草稿"));

        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10001);
        for (String key : List.of("   ", "k".repeat(129))) {
            error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key).content(body)), 400, 10001);
        }

        assertProjectCreationFacts(fixture, 0);
    }

    /** OPERATOR与VIEWER没有应用管理权限，拒绝必须发生在创建解析和持久化之前。 */
    @ParameterizedTest(name = "{0}不能创建应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能创建应用")
    void readOnlyRolesCannotCreateApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);

        error(write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-create-forbidden-" + role.name().toLowerCase())
                .content(createEnvelope("越权应用", validContent("越权草稿")))), 403, 60031);

        assertProjectCreationFacts(fixture, 0);
    }

    /** ARCHIVED项目即使请求者仍为OWNER也不能创建应用或幂等映射。 */
    @Test
    @DisplayName("归档项目拒绝应用创建")
    void archivedProjectRejectsApplicationCreation() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        error(write(fixture, post(basePath(fixture.projectId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-create-archived")
                .content(createEnvelope("归档应用", validContent("归档草稿")))), 403, 50017);

        assertProjectCreationFacts(fixture, 0);
    }

    /** 创建结果软删后不能用旧键重建或回放成功，否则删除状态会被客户端重试绕过。 */
    @Test
    @DisplayName("软删后的应用创建重放返回10014且不重建")
    void deletedApplicationCreationResultCannotBeReplayed() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        String path = basePath(fixture.projectId());
        String key = "application-create-deleted";
        String body = createEnvelope("待删应用", validContent("待删草稿"));
        JsonNode first = created(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)));
        UUID applicationId = UUID.fromString(first.path("id").asString());
        softDeleteApplication(applicationId);

        error(write(fixture, post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body)), 409, 10014);

        assertProjectCreationFacts(fixture, 1);
        assertThat(isApplicationDeleted(applicationId)).isTrue();
    }

    /** OPERATOR与VIEWER可读但不可写，两个入口都必须使用应用领域稳定403。 */
    @ParameterizedTest(name = "{0}不能修改应用")
    @EnumSource(value = ProjectRole.class, names = {"OPERATOR", "VIEWER"})
    @DisplayName("只读角色不能改名或保存草稿")
    void readOnlyRolesCannotMutateApplication(ProjectRole role) throws Exception {
        Fixture fixture = seed(role);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "只读名称", 0, 0, Instant.now(), draftContent("只读草稿"), false);

        error(write(fixture, patch(itemPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"越权名称\"}")), 403, 60031);
        error(write(fixture, put(draftPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent("越权草稿")))), 403, 60031);

        assertThat(applicationName(applicationId)).isEqualTo("只读名称");
        assertThat(draftRevision(applicationId)).isZero();
    }

    /** 已授权OWNER在ARCHIVED项目仍可读，但写许可必须在业务事务内拒绝并保持零漂移。 */
    @Test
    @DisplayName("归档项目拒绝应用改名和草稿保存")
    void archivedProjectRejectsApplicationWrites() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "归档名称", 0, 0, Instant.now(), draftContent("归档草稿"), false);
        setProjectStatus(fixture.projectId(), "ARCHIVED");

        error(write(fixture, patch(itemPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\"禁止名称\"}")), 403, 50017);
        error(write(fixture, put(draftPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("0", validContent("禁止草稿")))), 403, 50017);

        assertThat(applicationName(applicationId)).isEqualTo("归档名称");
        assertThat(draftRevision(applicationId)).isZero();
    }

    /** 合法字符串信封进入领域Title校验后仍须保持10001，不能被外层10002吞并。 */
    @Test
    @DisplayName("应用管理名称语义错误返回通用参数错误")
    void rejectsInvalidManagementNameAsParameterError() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "原管理名称", 0, 0, Instant.now(), draftContent("原草稿"), false);

        error(write(fixture, patch(itemPath(fixture.projectId(), applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"managementName\":\" \\t \"}")), 400, 10001);

        assertThat(applicationName(applicationId)).isEqualTo("原管理名称");
    }

    /** 信封解析只负责类型，revision词法与持久CAS分别保持60032和60033。 */
    @Test
    @DisplayName("expectedRevision要求字符串并区分非法词法与CAS冲突")
    void distinguishesRevisionEnvelopeSyntaxAndConflict() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "修订名称", 0, 0, Instant.now(), draftContent("修订草稿"), false);
        String path = draftPath(fixture.projectId(), applicationId);

        error(write(fixture, put(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"content\":" + validContent("数字修订") + "}")),
                400, 10002);
        for (String revision : List.of("00", "-1", "9223372036854775808")) {
            error(write(fixture, put(path).contentType(MediaType.APPLICATION_JSON)
                    .content(saveEnvelope(revision, validContent("非法修订")))), 400, 60032);
        }
        error(write(fixture, put(path).contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope("1", validContent("陈旧修订")))), 409, 60033);

        UUID exhaustedApplicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "耗尽名称", 0, Long.MAX_VALUE, Instant.now(), draftContent("耗尽草稿"), false);
        error(write(fixture, put(draftPath(fixture.projectId(), exhaustedApplicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveEnvelope(Long.toString(Long.MAX_VALUE), validContent("耗尽修订")))), 409, 60033);
        assertThat(draftRevision(exhaustedApplicationId)).isEqualTo(Long.MAX_VALUE);
    }

    /** 外层写信封必须封闭且保留重复字段证据，不能让普通JSON建树静默覆盖。 */
    @Test
    @DisplayName("改名与草稿信封拒绝未知字段和重复字段")
    void rejectsUnknownAndDuplicateEnvelopeFields() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "信封名称", 0, 0, Instant.now(), draftContent("信封草稿"), false);
        String item = itemPath(fixture.projectId(), applicationId);
        String draft = draftPath(fixture.projectId(), applicationId);

        for (String body : List.of(
                "{\"managementName\":\"甲\",\"unknown\":true}",
                "{\"managementName\":\"甲\",\"managementName\":\"乙\"}")) {
            error(write(fixture, patch(item).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10002);
        }
        for (String body : List.of(
                "{\"expectedRevision\":\"0\",\"content\":" + validContent("甲") + ",\"unknown\":true}",
                "{\"expectedRevision\":\"0\",\"expectedRevision\":\"0\",\"content\":"
                        + validContent("乙") + "}")) {
            error(write(fixture, put(draft).contentType(MediaType.APPLICATION_JSON).content(body)), 400, 10002);
        }

        assertThat(applicationName(applicationId)).isEqualTo("信封名称");
        assertThat(draftRevision(applicationId)).isZero();
    }

    /** content原文切片必须在建树前保留重复键与空白字节，交给既有草稿合同精确拒绝。 */
    @Test
    @DisplayName("草稿content保留原始字节和重复键证据")
    void preservesRawContentEvidenceForDraftValidation() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "原文名称", 0, 0, Instant.now(), draftContent("原文草稿"), false);
        String path = draftPath(fixture.projectId(), applicationId);
        String duplicate = """
                {"expectedRevision":"0","content":{
                  "formatVersion":"tc.application/v1","displayName":"甲","displayName":"乙"}}""";
        String whitespaceExpanded = "{\"expectedRevision\":\"0\",\"content\":{"
                + " ".repeat(65_536)
                + "\"formatVersion\":\"tc.application/v1\",\"displayName\":\"空白\"}}";

        JsonNode duplicateError = error(
                write(fixture, put(path).contentType(MediaType.APPLICATION_JSON).content(duplicate)), 400, 60032);
        JsonNode oversizedError = error(
                write(fixture, put(path).contentType(MediaType.APPLICATION_JSON).content(whitespaceExpanded)),
                400, 60032);
        assertThat(duplicateError.path("details").toString()).contains("DUPLICATE_KEY@$");
        assertThat(oversizedError.path("details").toString()).contains("RAW_TOO_LARGE@$");
        assertThat(draftRevision(applicationId)).isZero();
    }

    /** PATCH与PUT沿用公共完成墓碑：相同请求不重做，不同正文不复用同一键。 */
    @Test
    @DisplayName("应用写入口执行通用Idempotency-Key合同")
    void appliesCommonIdempotencyContractToApplicationWrites() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "幂等名称", 0, 0, Instant.now(), draftContent("幂等草稿"), false);
        String draftPath = draftPath(fixture.projectId(), applicationId);
        String draftBody = saveEnvelope("0", validContent("一次保存"));

        ok(write(fixture, put(draftPath).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-draft-once").content(draftBody)));
        error(write(fixture, put(draftPath).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-draft-once").content(draftBody)), 409, 10014);
        assertThat(draftRevision(applicationId)).isEqualTo(1);

        String itemPath = itemPath(fixture.projectId(), applicationId);
        ok(write(fixture, patch(itemPath).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-rename-once")
                .content("{\"managementName\":\"首次名称\"}")));
        error(write(fixture, patch(itemPath).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-rename-once")
                .content("{\"managementName\":\"冲突名称\"}")), 409, 10009);
        assertThat(applicationName(applicationId)).isEqualTo("首次名称");
    }

    /** 首次请求尚在真实业务行锁后等待时，同键重试必须返回10010且不能进入第二次CAS。 */
    @Test
    @DisplayName("应用写入口对在途同键请求返回10010")
    void reportsInProgressForConcurrentIdempotentWrite() throws Exception {
        Fixture fixture = seed(ProjectRole.OWNER);
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                "在途名称", 0, 0, Instant.now(), draftContent("在途草稿"), false);
        String path = draftPath(fixture.projectId(), applicationId);
        String body = saveEnvelope("0", validContent("在途保存"));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection lockConnection = fixtureOwnerConnection();
             PreparedStatement lock = lockConnection.prepareStatement(
                     "SELECT id FROM app_application WHERE id=? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lock.setObject(1, applicationId);
            assertThat(lock.executeQuery().next()).isTrue();

            Future<MvcResult> first = executor.submit(() -> write(fixture, put(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-draft-in-progress")
                    .content(body)));
            awaitInProgressRecord(fixture.projectId(), path);

            error(write(fixture, put(path).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "application-draft-in-progress")
                    .content(body)), 409, 10010);
            lockConnection.rollback();

            ok(first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(draftRevision(applicationId)).isEqualTo(1);
    }

    /** 断言目录响应最小字段、Long字符串和内部归属字段均不可见。 */
    private static void assertCatalog(
            JsonNode item, UUID applicationId, String managementName, long publicationRevision) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "appKey", "managementName", "publicationRevision",
                "currentVersionId", "createdAt", "updatedAt");
        assertThat(item.path("id").asString()).isEqualTo(applicationId.toString());
        assertThat(item.path("appKey").asString()).matches("app_[0-9a-f]{32}");
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

    /** 取得对象字段集合，断言新增字段也必须先变更公开合同。 */
    private static Set<String> fields(JsonNode node) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    /** 真实JWT携带当前项目，HTTP过滤器仍会回库复核成员与项目代次。 */
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

    /** 成功响应必须是精确200，业务错误JSON不得继续按数据解释。 */
    private static JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getResponse().getContentAsString());
    }

    /** 创建成功必须精确使用201，避免把普通200误当作已建立新资源。 */
    private static JsonNode created(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus())
                .as(response.getResponse().getContentAsString()).isEqualTo(201);
        return JSON.readTree(response.getResponse().getContentAsString());
    }

    /** 无正文写成功必须精确为204，且不能误带响应正文或资源定位头。 */
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

    /** 当前项目应用集合根路径。 */
    private static String basePath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/applications";
    }

    /** 当前项目单个应用目录路径。 */
    private static String itemPath(UUID projectId, UUID applicationId) {
        return basePath(projectId) + "/" + applicationId;
    }

    /** 当前项目单个应用草稿路径。 */
    private static String draftPath(UUID projectId, UUID applicationId) {
        return itemPath(projectId, applicationId) + "/draft";
    }

    /** 当前项目单个应用的历史版本集合路径。 */
    private static String versionsPath(UUID projectId, UUID applicationId) {
        return itemPath(projectId, applicationId) + "/versions";
    }

    /** 当前项目单个应用的精确历史版本路径。 */
    private static String versionPath(UUID projectId, UUID applicationId, UUID versionId) {
        return versionsPath(projectId, applicationId) + "/" + versionId;
    }

    /** 当前项目应用精确历史版本的回滚动作路径。 */
    private static String rollbackPath(UUID projectId, UUID applicationId, UUID versionId) {
        return versionPath(projectId, applicationId, versionId) + "/rollback";
    }

    /** 当前项目指定应用的发布撤回动作路径。 */
    private static String withdrawPath(UUID projectId, UUID applicationId) {
        return itemPath(projectId, applicationId) + "/withdraw";
    }

    /** 当前项目指定应用的一次性受控软删动作路径。 */
    private static String softDeletePath(UUID projectId, UUID applicationId) {
        return itemPath(projectId, applicationId) + "/soft-delete";
    }

    /** 生成合法最小content原文，测试名称均为固定可信文本。 */
    private static String validContent(String displayName) {
        return "{\"formatVersion\":\"tc.application/v1\",\"displayName\":\"" + displayName
                + "\",\"hostCompatibility\":{\"minInclusive\":\"1.0.0\","
                + "\"maxExclusive\":\"2.0.0\"},\"dashboardRefs\":[],\"entryDashboardId\":null}";
    }

    /** 生成应用发布严格信封，两个revision保持调用方给出的字符串表示。 */
    private static String publishEnvelope(String expectedDraftRevision, String expectedPublicationRevision) {
        return "{\"expectedDraftRevision\":" + JSON.valueToTree(expectedDraftRevision)
                + ",\"expectedPublicationRevision\":" + JSON.valueToTree(expectedPublicationRevision) + "}";
    }

    /** 生成只推进发布状态的严格信封，revision保持调用方给出的字符串表示。 */
    private static String publicationRevisionEnvelope(String expectedPublicationRevision) {
        return "{\"expectedPublicationRevision\":" + JSON.valueToTree(expectedPublicationRevision) + "}";
    }

    /** 生成草稿保存严格信封，保留content文本而不经JSON树重写。 */
    private static String saveEnvelope(String expectedRevision, String content) {
        return "{\"expectedRevision\":\"" + expectedRevision + "\",\"content\":" + content + "}";
    }

    /** 生成应用创建严格信封，保留content文本而不经JSON树重写。 */
    private static String createEnvelope(String managementName, String content) {
        return "{\"managementName\":\"" + managementName + "\",\"content\":" + content + "}";
    }

    /** 每例建立唯一租户、账号与项目，避免共享容器测试互删数据。 */
    private Fixture seed(ProjectRole role) throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID ownerId = role == ProjectRole.OWNER ? accountId : Uuid7.generate();
        UUID projectId = Uuid7.generate();
        fixtureProjectIds.add(projectId);
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES (?,?,(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD'))", tenantId, "应用接口租户");
            execute(connection, """
                    INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                    VALUES (?,?,'{noop}unused','应用接口账号',clock_timestamp())
                    """, accountId, accountId + "@example.com");
            execute(connection, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), tenantId, accountId);
            if (!ownerId.equals(accountId)) {
                execute(connection, """
                        INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                        VALUES (?,?,'{noop}unused','应用接口所有者',clock_timestamp())
                        """, ownerId, ownerId + "@example.com");
                execute(connection, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                        Uuid7.generate(), tenantId, ownerId);
            }
            execute(connection, """
                    INSERT INTO sys_project(id,tenant_id,name,region,project_key)
                    VALUES (?,?,'应用接口项目','sh-1',?)
                    """, projectId, tenantId, "appapi_" + projectId.toString().replace("-", ""));
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

    /** 以普通未发布状态建立分页夹具。 */
    private void insertApplication(Fixture fixture, UUID applicationId, String name, Instant updatedAt)
            throws SQLException {
        insertApplication(fixture.projectId(), fixture.tenantId(), fixture.accountId(),
                name, 0, 0, updatedAt, draftContent(name), false, applicationId);
    }

    /** 建立带服务端生成ID语义的应用夹具。 */
    private UUID insertApplication(
            UUID projectId, UUID tenantId, UUID accountId, String name,
            long publicationRevision, long draftRevision, Instant updatedAt,
            JsonNode content, boolean deleted) throws SQLException {
        UUID applicationId = Uuid7.generate();
        insertApplication(projectId, tenantId, accountId, name, publicationRevision,
                draftRevision, updatedAt, content, deleted, applicationId);
        return applicationId;
    }

    /** 同一连接插入目录与草稿，时间和ID由测试控制以证明键集分页。 */
    private void insertApplication(
            UUID projectId, UUID tenantId, UUID accountId, String name,
            long publicationRevision, long draftRevision, Instant updatedAt,
            JsonNode content, boolean deleted, UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            execute(connection, """
                    INSERT INTO app_application(
                        id,tenant_id,project_id,app_key,management_name,publication_revision,
                        created_by,updated_by,created_at,updated_at,deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, applicationId, tenantId, projectId, appKey(applicationId), name,
                    publicationRevision, accountId, accountId, updatedAt.minusSeconds(1), updatedAt,
                    deleted ? updatedAt.plusSeconds(1) : null);
            execute(connection, """
                    INSERT INTO app_application_draft(
                        application_id,tenant_id,project_id,content,revision,updated_by,created_at,updated_at)
                    VALUES (?,?,?,?::jsonb,?,?,?,?)
                    """, applicationId, tenantId, projectId, content.toString(), draftRevision,
                    accountId, updatedAt.minusSeconds(1), updatedAt);
            connection.commit();
        }
    }

    /** 规范公开定位符从稳定ID得到，保持32位小写十六进制。 */
    private static String appKey(UUID applicationId) {
        return "app_" + applicationId.toString().replace("-", "");
    }

    /** 最小读取夹具只需满足持久合同；完整发布资格由既有领域测试覆盖。 */
    private static JsonNode draftContent(String displayName) {
        return JSON.createObjectNode()
                .put("formatVersion", "tc.application/v1")
                .put("displayName", displayName);
    }

    /** 构造可被应用版本领域对象安全读取的封闭快照。 */
    private static JsonNode versionSnapshot(String displayName) {
        UUID dashboardId = Uuid7.generate();
        UUID dashboardVersionId = Uuid7.generate();
        tools.jackson.databind.node.ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("formatVersion", "tc.application/v1");
        snapshot.put("displayName", displayName);
        snapshot.putObject("hostCompatibility")
                .put("minInclusive", "1.0.0")
                .put("maxExclusive", "2.0.0");
        snapshot.putArray("dashboardRefs").addObject()
                .put("dashboardId", dashboardId.toString())
                .put("dashboardVersionId", dashboardVersionId.toString())
                .put("dashboardVersionNumber", "1")
                .put("title", "主看板")
                .put("schemaVersion", "tc.dashboard/v1")
                .put("schemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256")
                .put("schemaDigest", "a".repeat(64))
                .putArray("pages").addObject()
                .put("id", "overview")
                .put("title", "概览");
        snapshot.put("entryDashboardId", dashboardId.toString());
        snapshot.putArray("requiredSchemas").add("tc.dashboard/v1");
        snapshot.putArray("requiredComponents").addObject()
                .put("kind", "TEXT")
                .put("componentVersion", "1.0.0");
        snapshot.putArray("requiredResources").addObject()
                .put("resourceId", "builtin/logo")
                .put("digest", "b".repeat(64));
        return snapshot;
    }

    /** 建立当前可运行的精确看板版本，并用该版本组成可发布应用草稿。 */
    private PublishableApplication insertPublishableApplication(
            Fixture fixture, String displayName, long publicationRevision, long draftRevision) throws Exception {
        RunnableDashboard dashboard = insertRunnableDashboard(fixture, displayName + "看板");
        tools.jackson.databind.node.ObjectNode content = JSON.createObjectNode();
        content.put("formatVersion", "tc.application/v1");
        content.put("displayName", displayName);
        content.putObject("hostCompatibility")
                .put("minInclusive", "1.0.0")
                .put("maxExclusive", "2.0.0");
        content.putArray("dashboardRefs").addObject()
                .put("dashboardId", dashboard.dashboardId().toString())
                .put("dashboardVersionId", dashboard.versionId().toString())
                .put("title", "应用入口");
        content.put("entryDashboardId", dashboard.dashboardId().toString());
        UUID applicationId = insertApplication(
                fixture.projectId(), fixture.tenantId(), fixture.accountId(), displayName,
                publicationRevision, draftRevision, Instant.now(), content, false);
        return new PublishableApplication(applicationId, displayName, dashboard);
    }

    /** 经真实发布HTTP形成A/B两个不可变应用版本，回滚测试只操作其既有指针。 */
    private ApplicationRollbackFixture prepareApplicationRollbackFixture(
            Fixture fixture, String displayName) throws Exception {
        PublishableApplication application = insertPublishableApplication(fixture, displayName, 0, 0);
        String versions = versionsPath(fixture.projectId(), application.applicationId());
        JsonNode first = created(write(fixture, post(versions)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        JsonNode second = created(write(fixture, post(versions)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "1"))));
        return new ApplicationRollbackFixture(
                fixture,
                application,
                UUID.fromString(first.path("id").asString()),
                first.deepCopy(),
                UUID.fromString(second.path("id").asString()));
    }

    /** 经已验收的看板HTTP创建并发布自洽精确版本，避免SQL夹具绕过Schema默认值与摘要规范化。 */
    private RunnableDashboard insertRunnableDashboard(Fixture fixture, String title) throws Exception {
        String dashboardBasePath = "/api/v1/projects/" + fixture.projectId() + "/dashboards";
        String dashboardContent = "{\"schemaVersion\":\"tc.dashboard/v1\","
                + "\"presentation\":{\"mode\":\"RESPONSIVE_GRID\"},\"models\":[],\"variables\":[],"
                + "\"pages\":[{\"id\":\"overview\",\"title\":" + JSON.valueToTree(title)
                + ",\"components\":[]}]}";
        JsonNode dashboard = created(write(fixture, post(dashboardBasePath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "application-publish-dashboard-" + Uuid7.generate())
                .content("{\"managementName\":" + JSON.valueToTree(title)
                        + ",\"content\":" + dashboardContent + "}")));
        UUID dashboardId = UUID.fromString(dashboard.path("id").asString());
        JsonNode version = created(write(fixture, post(dashboardBasePath + "/" + dashboardId + "/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishEnvelope("0", "0"))));
        return new RunnableDashboard(
                dashboardId,
                UUID.fromString(version.path("id").asString()),
                title,
                version.path("schemaDigest").asString());
    }

    /** 用单个事务创建连续版本号，避免分页验收的51次反复建连。 */
    private void insertApplicationVersions(Fixture fixture, UUID applicationId, int count) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            connection.setAutoCommit(false);
            Instant base = Instant.parse("2026-09-06T20:00:00Z");
            for (int number = 1; number <= count; number++) {
                insertApplicationVersion(
                        connection, fixture, applicationId, number, number - 1L,
                        versionSnapshot("分页-" + number), base.plusSeconds(number));
            }
            connection.commit();
        }
    }

    /** 创建一条摘要与快照摘要一致的真实不可变应用版本。 */
    private VersionFixture insertApplicationVersion(
            Fixture fixture, UUID applicationId, long versionNumber,
            long sourceDraftRevision, JsonNode snapshot, Instant publishedAt) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            return insertApplicationVersion(
                    connection, fixture, applicationId, versionNumber,
                    sourceDraftRevision, snapshot, publishedAt);
        }
    }

    /** 在调用方事务内写版本，由PostgreSQL jsonb规范文本计算权威摘要。 */
    private VersionFixture insertApplicationVersion(
            Connection connection, Fixture fixture, UUID applicationId, long versionNumber,
            long sourceDraftRevision, JsonNode snapshot, Instant publishedAt) throws SQLException {
        UUID versionId = Uuid7.generate();
        Instant storedPublishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_application_version(
                    id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,?,?,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',
                        encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?,?)
                RETURNING snapshot_digest
                """)) {
            Object[] arguments = {
                    versionId, fixture.tenantId(), fixture.projectId(), applicationId,
                    versionNumber, sourceDraftRevision, snapshot.toString(), snapshot.toString(),
                    fixture.accountId(), storedPublishedAt
            };
            for (int index = 0; index < arguments.length; index++) {
                Object value = arguments[index];
                statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
            }
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new VersionFixture(
                        versionId, versionNumber, sourceDraftRevision,
                        snapshot.deepCopy(), result.getString("snapshot_digest"), storedPublishedAt);
            }
        }
    }

    /** 断言列表不装载快照或内部归属，并保留精确版本元数据。 */
    private static void assertVersionSummary(JsonNode item, VersionFixture expected) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision",
                "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");
        assertThat(item.path("id").asString()).isEqualTo(expected.id().toString());
        assertThat(item.path("versionNumber").isString()).isTrue();
        assertThat(item.path("versionNumber").asString()).isEqualTo(Long.toString(expected.versionNumber()));
        assertThat(item.path("sourceDraftRevision").isString()).isTrue();
        assertThat(item.path("sourceDraftRevision").asString())
                .isEqualTo(Long.toString(expected.sourceDraftRevision()));
        assertThat(item.path("snapshotDigestAlgorithm").asString()).isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(item.path("snapshotDigest").asString()).isEqualTo(expected.snapshotDigest());
        assertThat(Instant.parse(item.path("publishedAt").asString())).isEqualTo(expected.publishedAt());
    }

    /** 断言详情只在轻量摘要之上增加完整不可变快照。 */
    private static void assertVersionDetail(JsonNode item, VersionFixture expected) {
        assertThat(fields(item)).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "snapshot",
                "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");
        assertThat(item.path("id").asString()).isEqualTo(expected.id().toString());
        assertThat(item.path("versionNumber").isString()).isTrue();
        assertThat(item.path("versionNumber").asString()).isEqualTo(Long.toString(expected.versionNumber()));
        assertThat(item.path("sourceDraftRevision").isString()).isTrue();
        assertThat(item.path("sourceDraftRevision").asString())
                .isEqualTo(Long.toString(expected.sourceDraftRevision()));
        assertThat(item.path("snapshot")).isEqualTo(expected.snapshot());
        assertThat(item.path("snapshotDigestAlgorithm").asString()).isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(item.path("snapshotDigest").asString()).isEqualTo(expected.snapshotDigest());
        assertThat(Instant.parse(item.path("publishedAt").asString())).isEqualTo(expected.publishedAt());
    }

    /** 断言应用发布把草稿引用投影为不含Jackson内部类型的完整封闭快照。 */
    private static void assertPublishedSnapshot(JsonNode snapshot, PublishableApplication expected) {
        assertThat(fields(snapshot)).containsExactlyInAnyOrder(
                "formatVersion", "displayName", "hostCompatibility", "dashboardRefs",
                "entryDashboardId", "requiredSchemas", "requiredComponents", "requiredResources");
        assertThat(snapshot.path("formatVersion").asString()).isEqualTo("tc.application/v1");
        assertThat(snapshot.path("displayName").asString()).isEqualTo(expected.displayName());
        assertThat(fields(snapshot.path("hostCompatibility")))
                .containsExactlyInAnyOrder("minInclusive", "maxExclusive");
        assertThat(snapshot.path("hostCompatibility").path("minInclusive").asString()).isEqualTo("1.0.0");
        assertThat(snapshot.path("hostCompatibility").path("maxExclusive").asString()).isEqualTo("2.0.0");
        assertThat(snapshot.path("entryDashboardId").asString())
                .isEqualTo(expected.dashboard().dashboardId().toString());
        assertThat(snapshot.path("requiredSchemas")).containsExactly(JSON.valueToTree("tc.dashboard/v1"));
        assertThat(snapshot.path("requiredComponents")).isEmpty();
        assertThat(snapshot.path("requiredResources")).isEmpty();

        JsonNode reference = snapshot.path("dashboardRefs").get(0);
        assertThat(fields(reference)).containsExactlyInAnyOrder(
                "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title",
                "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
        assertThat(reference.path("dashboardId").asString())
                .isEqualTo(expected.dashboard().dashboardId().toString());
        assertThat(reference.path("dashboardVersionId").asString())
                .isEqualTo(expected.dashboard().versionId().toString());
        assertThat(reference.path("dashboardVersionNumber").isString()).isTrue();
        assertThat(reference.path("dashboardVersionNumber").asString()).isEqualTo("1");
        assertThat(reference.path("title").asString()).isEqualTo("应用入口");
        assertThat(reference.path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        assertThat(reference.path("schemaDigestAlgorithm").asString()).isEqualTo("PG_JSONB_TEXT_V1_SHA256");
        assertThat(reference.path("schemaDigest").asString()).isEqualTo(expected.dashboard().schemaDigest());
        assertThat(reference.path("pages")).hasSize(1);
        assertThat(fields(reference.path("pages").get(0))).containsExactlyInAnyOrder("id", "title");
        assertThat(reference.path("pages").get(0).path("id").asString()).isEqualTo("overview");
        assertThat(reference.path("pages").get(0).path("title").asString())
                .isEqualTo(expected.dashboard().title());
    }

    /** 从真实PostgreSQL读取一条新发布版本，响应必须逐字段等于该权威事实。 */
    private VersionFixture applicationVersion(UUID applicationId, UUID versionId) throws Exception {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id,version_number,source_draft_revision,snapshot,snapshot_digest,published_at
                       FROM app_application_version
                      WHERE application_id=? AND id=?
                     """)) {
            statement.setObject(1, applicationId);
            statement.setObject(2, versionId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new VersionFixture(
                        result.getObject("id", UUID.class),
                        result.getLong("version_number"),
                        result.getLong("source_draft_revision"),
                        JSON.readTree(result.getString("snapshot")),
                        result.getString("snapshot_digest"),
                        result.getTimestamp("published_at").toInstant());
            }
        }
    }

    /** 参数化PreparedStatement，防止夹具文本拼接改变JSON或标识含义。 */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                Object value = arguments[index];
                statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
            }
            statement.executeUpdate();
        }
    }

    /** 直接设置项目生命周期状态，以验证服务事务中的ACTIVE许可而非HTTP伪造。 */
    private void setProjectStatus(UUID projectId, String status) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE sys_project SET status=? WHERE id=?", status, projectId);
        }
    }

    /** 测试夹具模拟已验收的受控软删结果，只改变目标目录可见性并保留其聚合与创建映射。 */
    private void softDeleteApplication(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE app_application SET deleted_at=clock_timestamp() WHERE id=?", applicationId);
        }
    }

    /** 让历史应用版本引用的看板目录失去当前可运行资格，回滚必须在锁内重验并失败关闭。 */
    private void softDeleteDashboard(UUID dashboardId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE dash_dashboard SET deleted_at=clock_timestamp() WHERE id=?", dashboardId);
        }
    }

    /** 把发布revision推到Long上界，证明回滚不会溢出或绕过受控函数的耗尽保护。 */
    private void setApplicationPublicationRevision(UUID applicationId, long revision) throws SQLException {
        try (Connection connection = fixtureOwnerConnection()) {
            execute(connection, "UPDATE app_application SET publication_revision=? WHERE id=?", revision, applicationId);
        }
    }

    /** 查询目录软删事实，确认10014后没有用新身份覆盖旧结果。 */
    private boolean isApplicationDeleted(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT deleted_at IS NOT NULL FROM app_application WHERE id=?")) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    /** 直接核对目录、草稿、创建映射与成功审计数量，证明重放和拒绝均没有隐含副作用。 */
    private void assertProjectCreationFacts(Fixture fixture, long expected) throws SQLException {
        assertThat(countRows("SELECT count(*) FROM app_application WHERE project_id=?", fixture.projectId()))
                .isEqualTo(expected);
        assertThat(countRows("SELECT count(*) FROM app_application_draft WHERE project_id=?", fixture.projectId()))
                .isEqualTo(expected);
        assertThat(countRows(
                "SELECT count(*) FROM app_application_creation_result WHERE project_id=?",
                fixture.projectId())).isEqualTo(expected);
        assertThat(countRows(
                "SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='application.created'",
                fixture.projectId())).isEqualTo(expected);
    }

    /** 执行一个返回单行count(*)的参数化查询。 */
    private long countRows(String sql, Object... arguments) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 从真实持久事实读取管理名称，拒绝用HTTP缓存响应猜测是否发生越权写入。 */
    private String applicationName(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT management_name FROM app_application WHERE id=?")) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    /** 从真实持久事实读取草稿revision，证明拒绝和幂等重放均没有第二次写。 */
    private long draftRevision(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revision FROM app_application_draft WHERE application_id=?")) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 同时读取草稿revision与规范JSON文本，证明撤回没有改写独立草稿轴。 */
    private String applicationDraftFact(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revision,content::text FROM app_application_draft WHERE application_id=?")) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1) + ":" + result.getString(2);
            }
        }
    }

    /** 读取发布revision、当前指针、版本数与精确看板关系数，证明HTTP发布原子提交或零写失败。 */
    private String applicationPublicationState(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT application.publication_revision, application.current_version_id,
                            count(DISTINCT version.id) AS version_count,
                            count(reference.application_version_id) AS reference_count
                       FROM app_application application
                       LEFT JOIN app_application_version version
                         ON version.application_id=application.id
                       LEFT JOIN app_application_version_dashboard_ref reference
                         ON reference.application_version_id=version.id
                      WHERE application.id=?
                      GROUP BY application.publication_revision, application.current_version_id
                     """)) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong("publication_revision") + ":"
                        + result.getObject("current_version_id", UUID.class) + ":"
                        + result.getLong("version_count") + ":" + result.getLong("reference_count");
            }
        }
    }

    /** 读取应用发布成功审计数，公共重放和拒绝不得制造第二条审计。 */
    private long applicationPublicationAuditCount(UUID applicationId) throws SQLException {
        return countRows("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='application' AND target_id=? AND action='application.published'
                """, applicationId);
    }

    /** 读取全部版本与精确关系的稳定事实快照，证明回滚只切目录指针而不改历史。 */
    private List<String> applicationHistoryFacts(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT version.id,version.version_number,version.source_draft_revision,
                            version.snapshot::text,version.snapshot_digest_algorithm,version.snapshot_digest,
                            count(reference.application_version_id) AS reference_count
                       FROM app_application_version version
                       LEFT JOIN app_application_version_dashboard_ref reference
                         ON reference.application_version_id=version.id
                      WHERE version.application_id=?
                      GROUP BY version.id,version.version_number,version.source_draft_revision,
                               version.snapshot,version.snapshot_digest_algorithm,version.snapshot_digest
                      ORDER BY version.version_number
                     """)) {
            statement.setObject(1, applicationId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                List<String> facts = new ArrayList<>();
                while (result.next()) {
                    facts.add(result.getObject("id", UUID.class) + ":"
                            + result.getLong("version_number") + ":"
                            + result.getLong("source_draft_revision") + ":"
                            + result.getString("snapshot") + ":"
                            + result.getString("snapshot_digest_algorithm") + ":"
                            + result.getString("snapshot_digest") + ":"
                            + result.getLong("reference_count"));
                }
                return facts;
            }
        }
    }

    /** 读取应用回滚成功审计数，拒绝、重放和在途冲突均不得制造额外审计。 */
    private long applicationRollbackAuditCount(UUID applicationId) throws SQLException {
        return countRows("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='application' AND target_id=? AND action='application.rolled_back'
                """, applicationId);
    }

    /** 读取应用撤回审计数，证明204、重试与在途竞争只产生一次成功事实。 */
    private long applicationWithdrawalAuditCount(UUID applicationId) throws SQLException {
        return countRows("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='application' AND target_id=? AND action='application.withdrawn'
                """, applicationId);
    }

    /** 读取应用软删审计数，重复调用与公共墓碑重放不得制造第二条成功事实。 */
    private long applicationDeletionAuditCount(UUID applicationId) throws SQLException {
        return countRows("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='application' AND target_id=? AND action='application.deleted'
                """, applicationId);
    }

    /** 统计目录、草稿、版本、关系和创建恢复映射，证明软删不提前执行项目物理清理。 */
    private String applicationRetentionFacts(UUID applicationId) throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT (SELECT count(*) FROM app_application WHERE id=?) AS application_count,
                            (SELECT count(*) FROM app_application_draft WHERE application_id=?) AS draft_count,
                            (SELECT count(*) FROM app_application_version WHERE application_id=?) AS version_count,
                            (SELECT count(*)
                               FROM app_application_version_dashboard_ref reference
                               JOIN app_application_version version ON version.id=reference.application_version_id
                              WHERE version.application_id=?) AS reference_count,
                            (SELECT count(*) FROM app_application_creation_result WHERE application_id=?)
                                AS creation_result_count
                     """)) {
            for (int index = 1; index <= 5; index++) {
                statement.setObject(index, applicationId);
            }
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong("application_count") + ":"
                        + result.getLong("draft_count") + ":"
                        + result.getLong("version_count") + ":"
                        + result.getLong("reference_count") + ":"
                        + result.getLong("creation_result_count");
            }
        }
    }

    /** 等待公共幂等抢占提交后再发重试，避免用线程启动顺序猜测请求已到达过滤器。 */
    private void awaitInProgressRecord(UUID projectId, String path) throws Exception {
        awaitInProgressRecord(projectId, "PUT", path);
    }

    /** 等待指定HTTP方法的公共幂等抢占提交，供发布POST复用同一真实在途观察。 */
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
        throw new AssertionError("首次应用写请求未进入幂等IN_PROGRESS状态");
    }

    /** 一次HTTP测试使用的可信身份和项目归属。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {
    }

    /** 应用草稿所引用的当前可运行看板版本。 */
    private record RunnableDashboard(UUID dashboardId, UUID versionId, String title, String schemaDigest) {
    }

    /** 可发布应用与其精确看板依赖。 */
    private record PublishableApplication(
            UUID applicationId, String displayName, RunnableDashboard dashboard) {
    }

    /** 两个不可变历史版本及当前指针组成的真实回滚夹具。 */
    private record ApplicationRollbackFixture(
            Fixture fixture,
            PublishableApplication application,
            UUID firstVersionId,
            JsonNode firstVersion,
            UUID secondVersionId) {
    }

    /** HTTP版本响应需精确复核的持久夹具。 */
    private record VersionFixture(
            UUID id,
            long versionNumber,
            long sourceDraftRevision,
            JsonNode snapshot,
            String snapshotDigest,
            Instant publishedAt) {
    }
}
