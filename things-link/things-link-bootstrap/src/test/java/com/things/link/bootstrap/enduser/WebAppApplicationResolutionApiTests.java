package com.things.link.bootstrap.enduser;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebApp登录前应用公开定位HTTP边界的真实集成验收。
 *
 * <p>S12-2a2b只验证公开GET的精确安全路由、最小响应、缓存和输入闭集；受限函数ACL、RLS及全部
 * 生命周期矩阵已由2a2a专库测试覆盖，本类只用共享真实PostgreSQL准备一个代表性已发布应用。</p>
 */
@DisplayName("WebApp应用公开定位HTTP")
@AutoConfigureMockMvc
class WebAppApplicationResolutionApiTests extends AbstractIntegrationTest {

    /** 冻结语法的公开应用键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";

    /** 当前不可变发布版本中的中文展示名，用于同时验证字段来源与UTF-8响应。 */
    private static final String DISPLAY_NAME = "华东工厂能源总览";

    /** 与管理名、内部UUID相互独立的稳定项目键。 */
    private static final String PROJECT_KEY = "runtime-public-project";

    /** MockMvc经过完整App安全链和MVC控制器。 */
    @Autowired
    private MockMvc mockMvc;

    /** 解析成功与错误响应的Bootstrap统一Jackson实例。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 仅用于准备和清理权威夹具的迁移owner访问器。 */
    private JdbcTemplate owner;

    /** 当前夹具租户身份。 */
    private UUID tenantId;

    /** 当前夹具项目身份。 */
    private UUID projectId;

    /** 当前夹具应用目录身份。 */
    private UUID applicationId;

    /** 当前夹具不可变发布版本身份。 */
    private UUID applicationVersionId;

    /** 当前夹具发布账号身份。 */
    private UUID accountId;

    /** 每例建立独立已发布应用，避免共享容器中的生命周期修改相互污染。 */
    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        applicationId = UUID.randomUUID();
        applicationVersionId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        seedPublishedApplication();
    }

    /** 按外键逆序只清理本例随机身份，不清空其他并发或共享测试夹具。 */
    @AfterEach
    void cleanup() {
        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?", applicationId);
        owner.update("DELETE FROM app_application_version WHERE application_id = ?", applicationId);
        owner.update("DELETE FROM app_application WHERE id = ?", applicationId);
        owner.update("DELETE FROM sys_project WHERE id = ?", projectId);
        owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", tenantId, accountId);
        owner.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        owner.update("DELETE FROM sys_account WHERE id = ?", accountId);
    }

    /** 匿名规范GET只返回三个公开来源字段，并满足UTF-8、2KiB及禁止缓存合同。 */
    @Test
    void anonymousCanonicalGetReturnsBoundedPublicProjection() throws Exception {
        MvcResult result = mockMvc.perform(get(resolvePath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appKey").value(APP_KEY))
                .andExpect(jsonPath("$.displayName").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.projectKey").value(PROJECT_KEY))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("appKey", "displayName", "projectKey");
        assertThat(result.getResponse().getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(result.getResponse().getContentAsByteArray()).hasSizeLessThanOrEqualTo(2 * 1024);
        assertNoStoreWithoutEntityTag(result);
    }

    /** 当前发布事实消失时使用统一60023，且错误响应同样不得缓存或携带实体标签。 */
    @Test
    void unavailablePublicationUsesStableHiddenError() throws Exception {
        owner.update("UPDATE app_application SET current_version_id = NULL WHERE id = ?", applicationId);

        MvcResult result = mockMvc.perform(get(resolvePath()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(60023))
                .andReturn();

        assertNoStoreWithoutEntityTag(result);
    }

    /** 公开路径仍是无query、无body的封闭读取；额外输入不能被MVC静默忽略。 */
    @Test
    void queryOrBodyOutsideClosedContractUsesParameterError() throws Exception {
        MvcResult query = mockMvc.perform(get(resolvePath()).queryParam("unexpected", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andReturn();
        MvcResult body = mockMvc.perform(get(resolvePath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andReturn();

        assertNoStoreWithoutEntityTag(query);
        assertNoStoreWithoutEntityTag(body);
    }

    /** 只有GET、规范appKey及精确末尾公开；其他方法、相邻路径和非法键在Controller前拒绝。 */
    @Test
    void onlyCanonicalExactGetIsPublic() throws Exception {
        List<MvcResult> denied = List.of(
                mockMvc.perform(post(resolvePath())).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(head(resolvePath())).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/v1/app/applications/{appKey}/current", APP_KEY))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get(resolvePath() + "/extra"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/v1/app/applications/{appKey}/resolve",
                                "APP_0123456789abcdef0123456789abcdef"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/v1/app/applications/{appKey}/resolve", "app_0123"))
                        .andExpect(status().isUnauthorized()).andReturn());

        denied.forEach(result -> {
            assertThat(result.getHandler()).isNull();
            assertNoStoreWithoutEntityTag(result);
        });
    }

    /** 条件GET不能把公开定位降成304，也不能生成可跨生命周期复用的ETag。 */
    @Test
    void conditionalGetAlwaysRechecksAndReturnsFreshRepresentation() throws Exception {
        MvcResult result = mockMvc.perform(get(resolvePath())
                        .header(HttpHeaders.IF_NONE_MATCH, "\"stale-runtime-resolution\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appKey").value(APP_KEY))
                .andReturn();

        assertNoStoreWithoutEntityTag(result);
    }

    /** 创建最小项目、目录及带权威摘要的不可变当前版本。 */
    private void seedPublishedApplication() {
        owner.update("INSERT INTO sys_tenant(id, name) VALUES (?, '公开定位租户')", tenantId);
        owner.update("""
                INSERT INTO sys_account(id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', '公开定位发布人')
                """, accountId, accountId + "@example.com");
        owner.update("""
                INSERT INTO sys_tenant_member(id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), tenantId, accountId);
        owner.update("""
                INSERT INTO sys_project(id, tenant_id, name, region, project_key, status)
                VALUES (?, ?, '公开定位项目', 'sh-1', ?, 'ACTIVE')
                """, projectId, tenantId, PROJECT_KEY);
        owner.update("""
                INSERT INTO app_application(
                    id, tenant_id, project_id, app_key, management_name, created_by, updated_by)
                VALUES (?, ?, ?, ?, '仅供Console管理', ?, ?)
                """, applicationId, tenantId, projectId, APP_KEY, accountId, accountId);
        insertPublishedVersion();
        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 1
                 WHERE id = ?
                """, applicationVersionId, applicationId);
    }

    /** 快照使用生产字段闭集并由真实PostgreSQL规范JSONB文本计算摘要，避免伪成功夹具。 */
    private void insertPublishedVersion() {
        UUID dashboardId = UUID.randomUUID();
        UUID dashboardVersionId = UUID.randomUUID();
        owner.update("""
                WITH candidate AS (
                    SELECT jsonb_build_object(
                        'formatVersion', 'tc.application/v1',
                        'displayName', ?,
                        'hostCompatibility', jsonb_build_object(
                            'minInclusive', '1.0.0', 'maxExclusive', '2.0.0'),
                        'dashboardRefs', jsonb_build_array(jsonb_build_object(
                            'dashboardId', ?, 'dashboardVersionId', ?, 'dashboardVersionNumber', '1',
                            'title', '生产总览', 'schemaVersion', 'tc.dashboard/v1',
                            'schemaDigestAlgorithm', 'PG_JSONB_TEXT_V1_SHA256',
                            'schemaDigest', repeat('c', 64),
                            'pages', jsonb_build_array(jsonb_build_object('id', 'main', 'title', '总览')))),
                        'entryDashboardId', ?,
                        'requiredSchemas', jsonb_build_array('tc.dashboard/v1'),
                        'requiredComponents', '[]'::jsonb,
                        'requiredResources', '[]'::jsonb) AS snapshot
                )
                INSERT INTO app_application_version(
                    id, tenant_id, project_id, application_id, version_number,
                    source_draft_revision, snapshot, snapshot_digest_algorithm,
                    snapshot_digest, published_by_account_id, published_at)
                SELECT ?, ?, ?, ?, 1, 0, snapshot, 'PG_JSONB_TEXT_V1_SHA256',
                       encode(digest(convert_to(snapshot::text, 'UTF8'), 'sha256'), 'hex'), ?, now()
                  FROM candidate
                """, DISPLAY_NAME, dashboardId.toString(), dashboardVersionId.toString(), dashboardId.toString(),
                applicationVersionId, tenantId, projectId, applicationId, accountId);
    }

    /** @return 当前夹具的规范公开resolve路径 */
    private static String resolvePath() {
        return "/api/v1/app/applications/" + APP_KEY + "/resolve";
    }

    /** 成功与错误都必须明确禁止缓存，且不能生成条件请求所需实体标签。 */
    private static void assertNoStoreWithoutEntityTag(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(304);
    }
}
