package com.things.link.bootstrap.device.model;

import com.things.link.device.application.ThingModelVersionService;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * ADR0059 真实 API 验收：首次版本绑定后固定设备类型，未版本化设备首次换到已发布类型须原子建立INITIAL。
 * 正常版本与历史由发布、创建、明确换型API及真实版本发布服务产生；仅标记的旧异常用例通过限定身份SQL准备前置。
 * 每例随机独占项目，不全表清理，不把本片运行时保护冒称后续数据库归属守卫已交付。
 */
@AutoConfigureMockMvc
@DisplayName("D-112 设备换型与当前物模型版本归属")
class DeviceTypeChangeVersionApiTests extends AbstractIntegrationTest {

    /** API 与数据库 JSONB 快照共用相同解析器，不以字符串片段误判版本归属。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 仅隔离测试环境新建账号使用的本地口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** ADR0059/ERROR_CODES：版本化设备换型或首次绑定前置异常，整个请求以409/30063回滚。 */
    private static final int VERSION_CONFLICT = 30063;
    /** 被验收的设备创建与修改穿过真实HTTP认证、权限与事务链，后续版本夹具另由发布服务生成。 */
    @Autowired private MockMvc mockMvc;
    /** 仅准备本账号邮箱验证和VIEWER成员、读取项目身份及发布快照，设备与版本事实不由此直接伪造。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 初始化随机账号前清除本地登录限流，避免请求节奏掩盖业务反例。 */
    @Autowired private AuthRateLimiter rateLimiter;
    /** 尚无版本发布HTTP端点，使用真实事务服务产生第二版，避免原始INSERT伪造版本选择证据。 */
    @Autowired private ThingModelVersionService versionService;
    /** 同一应用事务内验证实际数据库角色并调用发布代理，保证版本夹具符合真实权限范围。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 两个已发布类型同为DIRECT，拓扑角色一致不能证明当前版本归属一致。 */
    @Test
    void changingToAnotherPublishedTypeWithoutTransitionMustNotCommitOldVersionOwnership() throws Exception {
        Fixture fixture = publishedDeviceFixture();
        JsonNode before = snapshot(fixture);
        assertInitialBinding(before, fixture);

        MvcResult changed = updateDevice(fixture, fixture.typeBId(), "不应部分提交的换型名称");
        JsonNode after = snapshot(fixture);
        assertSafeRejection(fixture, before, after, changed);
    }

    /** 显式清空类型同样不能保留旧类型版本指针；NULL外键不意味着版本语义仍然有效。 */
    @Test
    void clearingPublishedDeviceTypeWithoutTransitionMustNotLeaveOldVersionOwnership() throws Exception {
        Fixture fixture = publishedDeviceFixture();
        JsonNode before = snapshot(fixture);
        assertInitialBinding(before, fixture);

        MvcResult changed = updateDevice(fixture, null, "不应部分提交的清空名称");
        JsonNode after = snapshot(fixture);
        assertSafeRejection(fixture, before, after, changed);
    }

    /** 已版本化设备的同类型基本信息编辑继续成功，不能借名称编辑隐式升级或重建INITIAL。 */
    @Test
    void sameTypeMetadataEditPreservesCurrentVersionAndHistory() throws Exception {
        Fixture fixture = publishedDeviceFixture();
        JsonNode before = snapshot(fixture);
        assertInitialBinding(before, fixture);
        MvcResult result = updateDevice(fixture, fixture.typeAId(), "允许修改的名称");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = snapshot(fixture);
        assertThat(after.get("device").get("name").asString()).isEqualTo("允许修改的名称");
        assertThat(after.get("device").get("device_type_id")).isEqualTo(before.get("device").get("device_type_id"));
        assertThat(after.get("device").get("thing_model_version_id")).isEqualTo(before.get("device").get("thing_model_version_id"));
        assertThat(after.get("current_version")).isEqualTo(before.get("current_version"));
        assertThat(after.get("history")).isEqualTo(before.get("history"));
    }

    /** DRAFT或NULL类型且没有历史时，明确换到PUBLISHED类型原子绑定最新具体版本，重复请求不能追加第二条INITIAL。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unversionedDraftOrUntypedDeviceInitializesPublishedTargetExactlyOnce(boolean draftSource) throws Exception {
        Fixture fixture = unversionedDeviceFixture(draftSource);
        assertUnversioned(snapshot(fixture));
        ThingModelVersion latest = draftSource ? publishNextVersion(fixture) : null;
        MvcResult result = updateDevice(fixture, fixture.typeBId(), "首次选择已发布类型");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode initialized = snapshot(fixture);
        assertInitialBinding(initialized, fixture.typeBId());
        assertThat(initialized.get("device").get("id").asString()).isEqualTo(fixture.deviceId().toString());
        assertThat(initialized.get("current_version").get("version_number").asString())
                .isEqualTo(latest == null ? "1.0.0" : latest.versionNumber());
        if (latest != null) {
            assertThat(initialized.get("device").get("thing_model_version_id").asString())
                    .as("目标已有两版，首次绑定必须选择真实发布得到的最新具体ID")
                    .isEqualTo(latest.id().toString());
        }

        assertThat(updateDevice(fixture, fixture.typeBId(), "首次选择已发布类型").getResponse().getStatus()).isEqualTo(200);
        JsonNode retry = snapshot(fixture);
        assertThat(retry.get("device").get("thing_model_version_id")).isEqualTo(initialized.get("device").get("thing_model_version_id"));
        assertThat(retry.get("current_version")).isEqualTo(initialized.get("current_version"));
        assertThat(retry.get("history")).isEqualTo(initialized.get("history"));
    }

    /** 未版本化设备换到其他草稿或清空类型均合法，普通档案修改不能凭空创建版本或INITIAL。 */
    @Test
    void unversionedDraftChangesAndClearDoNotInventVersionHistory() throws Exception {
        Fixture fixture = unversionedDeviceFixture(true);
        UUID anotherDraft = createDraftType(fixture.login(), fixture.projectId(), "another_draft");
        assertUnversioned(snapshot(fixture));
        assertThat(updateDevice(fixture, anotherDraft, "换草稿").getResponse().getStatus()).isEqualTo(200);
        JsonNode changed = snapshot(fixture);
        assertThat(changed.get("device").get("device_type_id").asString()).isEqualTo(anotherDraft.toString());
        assertUnversioned(changed);
        assertThat(updateDevice(fixture, null, "清空未版本化类型").getResponse().getStatus()).isEqualTo(200);
        JsonNode cleared = snapshot(fixture);
        assertThat(cleared.get("device").get("device_type_id").isNull()).isTrue();
        assertUnversioned(cleared);
    }

    /** 已有授权/资源错误优先于新版本冲突，避免跨项目请求探测版本化状态。 */
    @Test
    void crossProjectResourcesAndViewerKeepExistingRejectionBeforeVersionChecks() throws Exception {
        Fixture fixture = publishedDeviceFixture();
        Fixture other = unversionedDeviceFixture(true);
        JsonNode before = snapshot(fixture);
        JsonNode otherBefore = snapshot(other);
        assertRejectedUnchanged(fixture, before,
                updateDevice(fixture, other.typeBId(), "不可见目标类型"), 404, 30001);
        assertRejectedUnchanged(fixture, before,
                updateDevice(fixture.login(), fixture.projectId(), other.deviceId(), fixture.typeAId(), "跨项目设备"), 404, 30020);
        assertThat(snapshot(other)).isEqualTo(otherBefore);
        assertRejectedUnchanged(fixture, before,
                updateDevice(other.login(), fixture.projectId(), fixture.deviceId(), fixture.typeBId(), "非成员修改"), 404, 50001);

        UUID viewerAccountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM sys_project_member WHERE project_id = ? AND role = 'OWNER'", UUID.class, other.projectId());
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'VIEWER')",
                UUID.randomUUID(), fixture.projectId(), viewerAccountId);
        Login viewer = switchProject(other.login(), fixture.projectId());
        assertRejectedUnchanged(fixture, before,
                updateDevice(viewer, fixture.projectId(), fixture.deviceId(), fixture.typeBId(), "只读账号修改"), 403, 30024);
    }

    /** 旧异常夹具：类型被旧版本标为PUBLISHED却没有版本事实时，首次换入应409而不是保存空指针成功。 */
    @Test
    void publishedTargetWithoutVersionRejectsFirstBindingWithoutMutation() throws Exception {
        Fixture fixture = unversionedDeviceFixture(true);
        UUID missingVersionType = createDraftType(fixture.login(), fixture.projectId(), "missing_version");
        legacyWrite(fixture, "UPDATE public.dev_type SET status = 'PUBLISHED' WHERE project_id = ? AND id = ?",
                fixture.projectId(), missingVersionType);
        try {
            JsonNode before = snapshot(fixture);
            assertUnversioned(before);
            assertRejectedUnchanged(fixture, before,
                    updateDevice(fixture, missingVersionType, "不能部分提交的首次绑定"), 409, VERSION_CONFLICT);
        } finally {
            // 只恢复本用例精确注入的旧状态；不修改不可变发布版本或其他项目数据。
            legacyWrite(fixture, "UPDATE public.dev_type SET status = 'DRAFT' WHERE project_id = ? AND id = ?",
                    fixture.projectId(), missingVersionType);
        }
    }

    /** 旧版本可留下已选PUBLISHED但没有指针和历史的设备；同type名称编辑不得偷偷将它初始化。 */
    @Test
    void sameTypeEditDoesNotInitializeEmptyPointerWithoutHistory() throws Exception {
        Fixture fixture = unversionedDeviceFixture(true);
        legacyWrite(fixture, "UPDATE public.dev_device SET device_type_id = ? WHERE project_id = ? AND id = ?",
                fixture.typeBId(), fixture.projectId(), fixture.deviceId());
        JsonNode before = snapshot(fixture);
        assertUnversioned(before);
        assertThat(updateDevice(fixture, fixture.typeBId(), "仅修改名称不自动初始化").getResponse().getStatus()).isEqualTo(200);
        JsonNode after = snapshot(fixture);
        assertThat(after.get("device").get("device_type_id").asString()).isEqualTo(fixture.typeBId().toString());
        assertThat(after.get("device").get("name").asString()).isEqualTo("仅修改名称不自动初始化");
        assertUnversioned(after);
    }

    /**
     * 并列展示 HTTP、完整快照、指针归属与 INITIAL 历史证据；错误提交不得被仅观察某一字段掩盖。
     * ADR0059已冻结409/30063：已有版本或历史时不能靠普通PUT改变类型并绕过转换合同。
     */
    private void assertSafeRejection(Fixture fixture, JsonNode before, JsonNode after, MvcResult response) throws Exception {
        int actualCode = errorCode(response);
        assertSoftly(softly -> {
            softly.assertThat(response.getResponse().getStatus()).as("已版本化设备的类型固定").isEqualTo(409);
            softly.assertThat(actualCode).isEqualTo(VERSION_CONFLICT);
            softly.assertThat(after).as("拒绝应保持整个设备、当前版本及转换历史快照不变").isEqualTo(before);
            softly.assertThat(after.get("device").get("device_type_id"))
                    .as("当前设备类型应仍与已绑定版本所属类型一致")
                    .isEqualTo(after.get("current_version").get("device_type_id"));
            softly.assertThat(after.get("device").get("thing_model_version_id"))
                    .as("安全拒绝不得悄悄清空或替换当前版本指针")
                    .isEqualTo(before.get("device").get("thing_model_version_id"));
            softly.assertThat(after.get("history")).as("历史不能被更新设备基本信息的请求重写或删除")
                    .isEqualTo(before.get("history"));
            softly.assertThat(after.get("current_version").get("device_type_id"))
                    .as("真实版本依然属于原类型，不能把类型一致性当作已成立的前提")
                    .isEqualTo(before.get("device").get("device_type_id"));
        });
    }

    /** 先证明设备确实有API创建的当前版本及一条INITIAL事实，不能用未发布类型空指针得到假阳性。 */
    private void assertInitialBinding(JsonNode snapshot, Fixture fixture) {
        assertInitialBinding(snapshot, fixture.typeAId());
    }

    /** 首次换型后也必须具备同样的INITIAL与指针证据，但版本归属为本次明确选择的目标类型。 */
    private void assertInitialBinding(JsonNode snapshot, UUID expectedTypeId) {
        assertThat(snapshot.get("device").get("device_type_id").asString()).isEqualTo(expectedTypeId.toString());
        assertThat(snapshot.get("device").get("thing_model_version_id").isNull()).isFalse();
        assertThat(snapshot.get("current_version").get("device_type_id").asString()).isEqualTo(expectedTypeId.toString());
        assertThat(snapshot.get("current_version").get("id"))
                .isEqualTo(snapshot.get("device").get("thing_model_version_id"));
        assertThat(snapshot.get("history")).hasSize(1);
        JsonNode initial = snapshot.get("history").get(0);
        assertThat(initial.get("transition_type").asString()).isEqualTo("INITIAL");
        assertThat(initial.get("from_model_version_id").isNull()).isTrue();
        assertThat(initial.get("to_model_version_id")).isEqualTo(snapshot.get("device").get("thing_model_version_id"));
    }

    /** 原反例使用真实已发布类型A创建，类型B也发布且分类相同，确保新拒绝来自版本合同。 */
    private Fixture publishedDeviceFixture() throws Exception {
        return deviceFixture(true, true);
    }

    /** 初始草稿或NULL类型均没有当前版本；目标B使用真实发布API产生可用于首次绑定的版本。 */
    private Fixture unversionedDeviceFixture(boolean draftSource) throws Exception {
        return deviceFixture(false, draftSource);
    }

    /** 每例一个随机账号和独占项目，不全表清场；可选初始类型决定是否已有版本资格。 */
    private Fixture deviceFixture(boolean publishedSource, boolean typedSource) throws Exception {
        Login owner = registerAndLogin("device-version-type-" + UUID.randomUUID() + "@example.com");
        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"设备版本归属验收\",\"region\":\"sh-1\"}")).andReturn();
        assertThat(project.getResponse().getStatus()).isEqualTo(200);
        UUID projectId = responseId(project);
        Login scoped = switchProject(owner, projectId);
        UUID typeAId = publishedSource ? createAndPublishType(scoped, projectId, "version_type_a")
                : createDraftType(scoped, projectId, "version_type_a");
        UUID typeBId = createAndPublishType(scoped, projectId, "version_type_b");
        String sourceType = typedSource ? "\"" + typeAId + "\"" : "null";
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":%s,\"deviceKey\":\"version_device\",\"name\":\"原始设备名称\"}"
                        .formatted(sourceType))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return new Fixture(projectId, typeAId, typeBId, responseId(created), scoped);
    }

    /** 类型创建返回201且为DRAFT，避免把创建和发布混成一个不可检查的前置。 */
    private UUID createDraftType(Login login, UUID projectId, String key) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"typeKey":"%s","name":"%s","deviceKind":"DIRECT","payloadProtocol":"STANDARD","networkType":"WIFI"}
                        """.formatted(key, key))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(JSON.readTree(created.getResponse().getContentAsString()).get("status").asString()).isEqualTo("DRAFT");
        return responseId(created);
    }

    /** 发布返回200；版本与INITIAL只由真实发布/创建/换型路径产生。 */
    private UUID createAndPublishType(Login login, UUID projectId, String key) throws Exception {
        UUID id = createDraftType(login, projectId, key);
        MvcResult published = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + id + "/publish")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())).andReturn();
        assertThat(published.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(published.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(body.get("deviceKind").asString()).isEqualTo("DIRECT");
        return id;
    }

    /**
     * 使用已发布1.0.0的完整快照调用真实发布代理生成1.0.1；没有版本HTTP入口时仍不绕过发布合同。
     * 元数据在事务前读取，随后固定真实项目所有者范围与APP_ROLE，finally清理线程上下文。
     */
    private ThingModelVersion publishNextVersion(Fixture fixture) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, fixture.projectId());
        UUID accountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM sys_project_member WHERE project_id = ? AND role = 'OWNER'",
                UUID.class, fixture.projectId());
        TenantContext.set(new TenantScope(tenantId, fixture.projectId(), accountId));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                String initialSnapshot = jdbcTemplate.queryForObject("""
                        SELECT model_snapshot::text FROM dev_thing_model_version
                         WHERE project_id = ? AND device_type_id = ? AND version_number = '1.0.0'
                        """, String.class, fixture.projectId(), fixture.typeBId());
                ThingModelVersion published = versionService.publish(fixture.projectId(), fixture.typeBId(), "1.0.1",
                        ThingModelVersion.ChangeLevel.PATCH, JSON.readTree(initialSnapshot));
                assertThat(published.deviceTypeId()).isEqualTo(fixture.typeBId());
                assertThat(published.versionNumber()).isEqualTo("1.0.1");
                return published;
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 明确发送deviceTypeId或JSON null，不把DTO遗漏字段的语义与清空类型混为一谈。 */
    private MvcResult updateDevice(Fixture fixture, UUID newTypeId, String name) throws Exception {
        return updateDevice(fixture.login(), fixture.projectId(), fixture.deviceId(), newTypeId, name);
    }

    /** 显式独立身份/项目/设备参数用于验证原资源错误优先级，不绕过真实HTTP权限检查。 */
    private MvcResult updateDevice(Login login, UUID projectId, UUID deviceId, UUID newTypeId, String name) throws Exception {
        String typeJson = newTypeId == null ? "null" : "\"" + newTypeId + "\"";
        return mockMvc.perform(put("/api/v1/projects/" + projectId + "/devices/" + deviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":%s,\"name\":\"%s\"}".formatted(typeJson, name))).andReturn();
    }

    /** 指针、当前版本和历史三者同时为空才是从未版本化，不能只看一个NULL字段。 */
    private void assertUnversioned(JsonNode snapshot) {
        assertThat(snapshot.get("device").get("thing_model_version_id").isNull()).isTrue();
        assertThat(snapshot.get("current_version").isNull()).isTrue();
        assertThat(snapshot.get("history")).isEmpty();
    }

    /** 普通拒绝和旧异常拒绝都检查整个快照，不要求旧异常本来就满足当前归属不变式。 */
    private void assertRejectedUnchanged(Fixture fixture, JsonNode before, MvcResult response, int status, int code) throws Exception {
        JsonNode after = snapshot(fixture);
        int actualCode = errorCode(response);
        assertSoftly(softly -> {
            softly.assertThat(response.getResponse().getStatus()).isEqualTo(status);
            softly.assertThat(actualCode).isEqualTo(code);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** 误成功没有code时返回哨兵，保留HTTP及数据变化的并列断言，避免NullPointerException掩盖证据。 */
    private int errorCode(MvcResult response) throws Exception {
        String body = response.getResponse().getContentAsString();
        if (body.isBlank()) {
            return -1;
        }
        JsonNode code = JSON.readTree(body).get("code");
        return code == null ? -1 : code.asInt();
    }

    /**
     * 仅供专门标记的旧异常前置：限定本项目/主键的APP_ROLE写入，绝不禁用约束或篡改不可变版本。
     * d2数据库守卫上线后，受其拒绝的历史前置须移动到独立旧库升级测试。
     */
    private void legacyWrite(Fixture fixture, String sql, Object... arguments) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement scope = connection.prepareStatement("SELECT current_user, set_config('app.project_id', ?, true)")) {
                    scope.setString(1, fixture.projectId().toString());
                    try (ResultSet row = scope.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo(APP_ROLE);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    for (int index = 0; index < arguments.length; index++) {
                        update.setObject(index + 1, arguments[index]);
                    }
                    assertThat(update.executeUpdate()).as("旧异常前置只应修改本用例的一行").isEqualTo(1);
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
    }

    /** 单个APP_ROLE读取事务返回设备、不可变当前版本和全部转换事实，使用显式project范围与真实数据库快照。 */
    private JsonNode snapshot(Fixture fixture) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement scope = connection.prepareStatement(
                        "SELECT current_user, set_config('app.project_id', ?, true)")) {
                    scope.setString(1, fixture.projectId().toString());
                    try (ResultSet row = scope.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo(APP_ROLE);
                        assertThat(row.getString(2)).isEqualTo(fixture.projectId().toString());
                    }
                }
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT jsonb_build_object(
                            'device', to_jsonb(d), 'current_version', to_jsonb(v),
                            'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.effective_at, h.id)
                                FROM public.dev_device_model_binding_history h
                                WHERE h.project_id = d.project_id AND h.device_id = d.id), '[]'::jsonb))::text
                          FROM public.dev_device d LEFT JOIN public.dev_thing_model_version v
                            ON v.project_id = d.project_id AND v.id = d.thing_model_version_id
                         WHERE d.project_id = ? AND d.id = ?
                        """)) {
                    query.setObject(1, fixture.projectId());
                    query.setObject(2, fixture.deviceId());
                    try (ResultSet row = query.executeQuery()) {
                        assertThat(row.next()).as("项目RLS范围中应存在真实API设备").isTrue();
                        return JSON.readTree(row.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** 注册只补本账号邮箱验证这一登录前置，后续发布和设备变更均通过正常认证授权。 */
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(registration.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return loginResponse(login);
    }

    /** 项目切换会轮换刷新令牌，必须同时保存响应JWT与新Cookie，旧Cookie不能用于第二次切换。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        return loginResponse(switched);
    }

    /** 登录和项目切换均从当前响应提取完整令牌对，遵从RefreshTokenService的轮换合同。 */
    private Login loginResponse(MvcResult result) throws Exception {
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';'))).findFirst().orElseThrow();
        return new Login(JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(), refresh);
    }

    /** 创建响应的真实UUID是所有后续请求与数据库核验的唯一身份。 */
    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /**
     * 本片验收使用的真实API身份。
     * @param projectId 独占项目及RLS轴
     * @param typeAId 原DIRECT类型，是否发布由夹具场景决定
     * @param typeBId 另一已发布DIRECT类型
     * @param deviceId 真实创建的设备，按场景持有INITIAL或保持未版本化
     * @param login 项目所有者令牌
     */
    private record Fixture(UUID projectId, UUID typeAId, UUID typeBId, UUID deviceId, Login login) { }

    /**
     * 隔离账号的登录响应。
     * @param accessToken 后续授权请求JWT
     * @param refreshToken 项目切换使用的Cookie
     */
    private record Login(String accessToken, String refreshToken) { }
}
