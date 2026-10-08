package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 真实PG与完整认证链验证受控配置登记，不将测试能力声明当作制造或设备实机证据。 */
@AutoConfigureMockMvc
class OtaTypeBaselineHttpIntegrationTests extends AbstractIntegrationTest {
    /** 启动前固定独占范围，配置不能由请求正文替换。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 受限规范JSON仅用于构建公开测试配置。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 真实响应解码器。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 固定配置主体跨例复用，所有HTTP使用自然125毫秒间隔补充真实限流额度。 */
    private static long lastHttpRequestAt;
    /** 每例独占身份图。 */ private final List<Fixture> fixtures = new ArrayList<>();
    /** 完整HTTP安全过滤器链。 */ @Autowired private MockMvc mvc;
    /** 真正签发并由安全链验签。 */ @Autowired private TokenIssuer tokens;
    /** device本域普通RLS查询端口，不用owner查询代替断言。 */
    @Autowired private com.things.link.device.application.OtaDeviceTypeIdentityPort types;
    /** 已代理真实业务服务。 */ @Autowired private com.things.link.ota.application.OtaTypeBaselineService baselines;
    /** 多实例使用相同真实持久端口。 */ @Autowired private com.things.link.ota.domain.OtaTypeBaselineRepository repository;
    /** 正式项目授权与生命周期服务。 */ @Autowired private com.things.link.project.application.ProjectService projects;
    /** 独立于角色的项目持续许可。 */ @Autowired private com.things.link.project.application.ProjectLifecycleAccessService lifecycle;
    /** 当前资格调用者外层业务事务。 */ @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 仅在指定登记审计中注入故障，保留真实审计INSERT。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.things.link.support.audit.AuditLogService audit;

    /** 只配置测试公开能力，不声明任何实际制造凭据。 */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.type-baselines-json", () -> sourceJson(CONFIGURED, 1, 1024));
    }

    /** 服务器只从配置登记公开基线，HTTP不能注入能力，墓碑与版本约束保留。 */
    @Test
    void registersConfiguredBaselineAndRejectsBodyCapabilitiesAndRepeatedVersions() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        long auditBefore = registrationAuditCount(f);
        error(read(f, path(f)), 404, 70031);
        byte[] injected = CANONICAL.writeObject(Map.of("expectedRevision", "0", "supportsAbSlots", true));
        error(write(f, key(), injected), 400, 10002);
        String once = key();
        var result = write(f, once, revision("0"));
        JsonNode registered = ok(result);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(registered.propertyNames()).containsExactlyInAnyOrder("revision", "baselineHash", "baseline", "registeredAt", "updatedAt");
        assertThat(registered.path("revision").asText()).isEqualTo("1");
        assertThat(registered.path("baseline").path("supportsAbSlots").asBoolean()).isFalse();
        assertThat(registered.path("baseline").path("protectedSecurityCounterBits").asInt()).isZero();
        assertThat(registered.path("baseline").path("deviceTypeId").asText()).isEqualTo(f.typeId().toString());
        assertThat(registered.path("baseline")).isEqualTo(JSON.readTree(CANONICAL.writeObject(baseline(f, 1, 1024))));
        assertThat(registered.path("baselineHash").asText()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeObject(baseline(f, 1, 1024)))));
        assertThat(ok(read(f, path(f)))).isEqualTo(registered);
        MvcResult completed = write(f, once, revision("0"));
        error(completed, 409, 10014);
        assertThat(completed.getResponse().getHeader("Idempotency-Replayed")).isNull();
        assertThat(completed.getResponse().getContentAsString()).doesNotContain("baselineHash", "registeredAt", "availableRamBytes");
        // 首次正文不可重放，只读完整当前事实辅助恢复，不声称实际网络丢包已测试。
        MvcResult recovered = read(f, path(f));
        assertThat(recovered.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(ok(recovered)).isEqualTo(registered);
        error(write(f, once, revision("1")), 409, 10009);
        error(write(f, key(), revision("0")), 409, 70030);
        error(write(f, key(), revision("1")), 409, 70030);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isEqualTo(1);
        assertThat(ok(read(f, path(f)))).isEqualTo(registered);
        assertThat(registrationAuditCount(f)).isEqualTo(auditBefore + 1);
    }

    /** 配置不能替代真实当前成员、类型及产品映射，无配置范围明确拒绝登记。 */
    @Test
    void enforcesAuthenticationRoleScopeAndAuthoritativeProductMapping() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        assertThat(perform(post(path(f) + "/registrations").contentType("application/json").content(revision("0")))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(write(f, null, revision("0")).getResponse().getStatus()).isEqualTo(400);
        Fixture other = seedOther(ProjectRole.OWNER);
        error(write(other, key(), revision("0")), 503, 70028);
        error(read(other, path(f)), 404, 50001);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        error(write(f, key(), revision("0")), 403, 70032);
        error(read(f, path(f)), 403, 70032);
        owner().update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        owner().update("UPDATE dev_type SET product_key=? WHERE id=?", "different_product", f.typeId());
        error(write(f, key(), revision("0")), 422, 70029);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline WHERE project_id=?", Long.class, f.projectId())).isZero();
    }

    /** 管理GET和登记均受ACTIVE许可限制，旧项目JWT不能跨越成员移除继续消费事实。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"archived", "removed"})
    void rejectsArchivedOrRemovedManagementAccessWithoutIncrement(String change) throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode registered = ok(write(f, key(), revision("0")));
        long auditBefore = registrationAuditCount(f);
        Map<String,Object> before = baselineHead(f);
        String bearer = tokens.issue(new AuthenticatedPrincipal(f.accountId(), f.tenantId(), f.projectId())).value();
        if ("archived".equals(change)) owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        else owner().update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        int status = "archived".equals(change) ? 403 : 401;
        int code = "archived".equals(change) ? 50017 : 20020;
        // 先验证管理GET的实际分类，不能用公开历史读取口径替代该完整受控声明。
        error(perform(get(path(f)).header("Authorization", "Bearer " + bearer)), status, code);
        error(perform(post(path(f) + "/registrations").contentType("application/json")
                .header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key())
                .content(revision(registered.path("revision").asText()))), status, code);
        assertThat(baselineHead(f)).isEqualTo(before);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isEqualTo(1);
        assertThat(registrationAuditCount(f)).isEqualTo(auditBefore);
    }

    /** 精确来源配置不能替代当前已发布且未删除的真实类型，拒绝不留下头、历史或审计。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"draft", "deleted"})
    void rejectsConfiguredTypeWithoutCurrentPublishedIdentity(String change) throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        long auditBefore = registrationAuditCount(f);
        if ("draft".equals(change)) owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?", f.typeId());
        else owner().update("UPDATE dev_type SET deleted_at=now() WHERE id=?", f.typeId());
        error(write(f, key(), revision("0")), 422, 70029);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(registrationAuditCount(f)).isEqualTo(auditBefore);
    }

    /** device端口以真实普通RLS三轴查询，只返回已发布身份且不要求管理角色。 */
    @Test
    void deviceIdentityPortRespectsPublishedStateExactScopeAndRls() throws Exception {
        Fixture f = seed(ProjectRole.VIEWER);
        Fixture other = seedOther(ProjectRole.OWNER);
        var identity = transactional(f, () -> types.find(f.tenantId(), f.projectId(), f.typeId())).orElseThrow();
        assertThat(identity).isEqualTo(new com.things.link.device.application.OtaDeviceTypeIdentity(
                f.tenantId(), f.projectId(), f.typeId(), "product_" + f.typeId()));
        assertThat(transactional(f, () -> types.find(other.tenantId(), f.projectId(), f.typeId()))).isEmpty();
        assertThat(transactional(f, () -> types.find(f.tenantId(), other.projectId(), f.typeId()))).isEmpty();
        assertThat(transactional(other, () -> types.find(f.tenantId(), f.projectId(), f.typeId()))).isEmpty();
        owner().update("UPDATE dev_type SET status='DRAFT' WHERE id=?", f.typeId());
        assertThat(transactional(f, () -> types.find(f.tenantId(), f.projectId(), f.typeId()))).isEmpty();
    }

    /** 当前基线事务锁住权威类型，另一个真实连接的软删除收到明确锁超时。 */
    @Test
    void currentProjectionKeepsDeviceTypeStableUntilItsTransactionEnds() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        ok(write(f, key(), revision("0")));
        transactional(f, () -> {
            baselines.lockCurrent(f.tenantId(), f.projectId(), f.typeId());
            try (var other = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var setting = other.createStatement();
                 var update = other.prepareStatement("UPDATE dev_type SET deleted_at=now() WHERE id=?")) {
                setting.execute("SET lock_timeout='150ms'");
                update.setObject(1, f.typeId());
                assertThatThrownBy(update::executeUpdate).isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            return true;
        });
        try (var other = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var check = other.prepareStatement("SELECT id FROM dev_type WHERE id=? FOR UPDATE NOWAIT")) {
            check.setObject(1, f.typeId());
            try (var rows = check.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    /** 实际审计INSERT后的异常必须回滚当前头、历史和审计，而不是留半个登记。 */
    @Test
    void auditFailureRollsBackHeadHistoryAndAudit() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        long before = owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='ota.type.baseline.registered'", Long.class, f.projectId());
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("测试基线审计写后失败");
        }).when(audit).record(org.mockito.ArgumentMatchers.argThat(entry -> entry != null && f.projectId().equals(entry.projectId())
                && "ota.type.baseline.registered".equals(entry.action())));
        error(write(f, key(), revision("0")), 500, 90000);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='ota.type.baseline.registered'", Long.class, f.projectId())).isEqualTo(before);
        org.mockito.Mockito.doCallRealMethod().when(audit).record(org.mockito.ArgumentMatchers.any());
        ok(write(f, key(), revision("0")));
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='ota.type.baseline.registered'", Long.class, f.projectId())).isEqualTo(before + 1);
    }

    /** 更高受控配置登记后旧进程不得取当前资格；GET仍能读取持久元数据。 */
    @Test
    void newerImmutableConfigurationInvalidatesOldCurrentProjectionWithoutHidingMetadata() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        ok(write(f, key(), revision("0")));
        assertThatThrownBy(() -> scoped(f, () -> baselines.lockCurrent(f.tenantId(), f.projectId(), f.typeId())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var current = transactional(f, () -> baselines.lockCurrent(f.tenantId(), f.projectId(), f.typeId()));
        assertThat(current.state().revision()).isEqualTo(1);
        var newer = new com.things.link.ota.application.OtaTypeBaselineService(repository,
                new com.things.link.ota.application.OtaTypeBaselineSource(sourceJson(f, 2, 2048)), types, projects, lifecycle, audit);
        transactional(f, () -> newer.register(f.projectId(), f.typeId(), key(), "1"));
        assertThat(transactional(f, () -> newer.lockCurrent(f.tenantId(), f.projectId(), f.typeId())).state().revision()).isEqualTo(2);
        assertThatThrownBy(() -> transactional(f, () -> baselines.lockCurrent(f.tenantId(), f.projectId(), f.typeId())))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70028));
        assertThat(ok(read(f, path(f))).path("revision").asText()).isEqualTo("2");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version WHERE project_id=?", Long.class, f.projectId())).isEqualTo(2);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        assertThat(transactional(f, () -> newer.lockCurrent(f.tenantId(), f.projectId(), f.typeId())).state().revision()).isEqualTo(2);
    }

    /** 完整公开基线；true能力只来自测试配置，不能外推设备具有这些能力。 */
    private static Map<String,Object> baseline(Fixture f, long version, long ram) {
        var b = new java.util.LinkedHashMap<String,Object>();
        b.put("contractVersion", "tc-ota-type-baseline/v1");
        b.put("tenantId", f.tenantId().toString()); b.put("projectId", f.projectId().toString());
        b.put("deviceTypeId", f.typeId().toString()); b.put("productKey", "product_" + f.typeId());
        b.put("baselineVersion", version); b.put("trustDomain", "baseline.test"); b.put("rootFingerprint", "a".repeat(64));
        b.put("hardware", Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        b.put("bootloader", Map.of("minimumVersion", "1.0.0", "maximumVersion", "2.0.0"));
        b.put("signatureProfiles", List.of("TC_OTA_ED25519_V1")); b.put("maximumArtifactBytes", 67108864L);
        b.put("availableRamBytes", ram); b.put("availableFlashBytes", 67108864L);
        b.put("supportsAbSlots", false); b.put("supportsRangeDownload", true); b.put("supportsResumeDownload", true);
        b.put("protectedSecurityCounterBits", 0L); b.put("compressionAlgorithms", List.of("NONE"));
        b.put("deltaModes", List.of("NONE")); b.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        b.put("evidenceReference", "test-fixture-only");
        return b;
    }

    /** 每个服务实例获得独立不可变配置字符串。 */
    private static String sourceJson(Fixture f, long version, long ram) {
        return new String(CANONICAL.writeObject(Map.of("baselines", List.of(baseline(f, version, ram)))), StandardCharsets.UTF_8);
    }

    /** 本例真实scope，设备内部调用不要求管理成员。 */
    private static <T> T scoped(Fixture f, java.util.function.Supplier<T> work) {
        return com.things.link.support.tenant.ScopedTenantWork.call(new com.things.link.shared.tenant.TenantScope(
                f.tenantId(), f.projectId(), f.accountId()), work);
    }

    /** 以实际事务复验MANDATORY内部资格。 */
    private <T> T transactional(Fixture f, java.util.function.Supplier<T> work) {
        return scoped(f, () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> work.get()));
    }

    /** 唯一基线路径。 */
    private static String path(Fixture f) { return "/api/v1/projects/" + f.projectId() + "/ota/device-types/" + f.typeId() + "/baseline"; }
    /** 规范登记请求只有修订，不包含能力。 */
    private static byte[] revision(String value) { return CANONICAL.writeObject(Map.of("expectedRevision", value)); }
    /** 请求键每次唯一。 */ private static String key() { return UUID.randomUUID().toString(); }
    /** 完整认证请求，不替代安全过滤器。 */
    private MvcResult request(Fixture f, MockHttpServletRequestBuilder builder) throws Exception {
        return perform(builder.header("Authorization", "Bearer " + tokens.issue(new AuthenticatedPrincipal(
                f.accountId(), f.tenantId(), f.projectId())).value()));
    }
    /** 匿名、当前JWT及冻结旧JWT共用自然节奏，不忽略429或放宽生产配额。 */
    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        paceHttpRequests();
        return mvc.perform(builder).andReturn();
    }
    /** 单次等待至125毫秒发送间隔，固定配置主体在参数例之间仍保留实际令牌补充。 */
    private static synchronized void paceHttpRequests() throws InterruptedException {
        long now = System.nanoTime();
        long remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(125) - (now - lastHttpRequestAt);
        if (lastHttpRequestAt != 0 && remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
        lastHttpRequestAt = System.nanoTime();
    }
    /** 写入保留精确原文。 */
    private MvcResult write(Fixture f, String key, byte[] body) throws Exception {
        var builder = post(path(f) + "/registrations").contentType("application/json").content(body);
        if (key != null) builder.header("Idempotency-Key", key);
        return request(f, builder);
    }
    /** 真实GET。 */ private MvcResult read(Fixture f, String path) throws Exception { return request(f, get(path)); }
    /** 保留失败首因，成功必须200。 */
    private static JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getResponse().getContentAsString());
    }
    /** 同时核对HTTP及错误码。 */
    private static void error(MvcResult response, int status, int code) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(response.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(code);
    }
    /** 固定项目跨例重建而审计不可删，只比较本例实际新增量。 */
    private static long registrationAuditCount(Fixture f) {
        return owner().queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='ota.type.baseline.registered'",
                Long.class, f.projectId());
    }
    /** 归档或失权后不能使用管理GET，owner仅观察已存在头的完整公开指针及原始时刻。 */
    private static Map<String,Object> baselineHead(Fixture f) {
        return owner().queryForMap("SELECT revision,baseline_version,baseline_hash,created_at,updated_at FROM ota_type_baseline WHERE project_id=? AND device_type_id=?",
                f.projectId(), f.typeId());
    }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource()))
                    .executeWithoutResult(status -> {
                        owner.update("DELETE FROM ota_type_baseline_version WHERE project_id = ?", fixture.projectId());
                        owner.update("DELETE FROM ota_type_baseline WHERE project_id = ?", fixture.projectId());
                    });
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
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

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) { return seedFixture(CONFIGURED, role); }
    /** 未配置范围的独立成员图。 */
    private Fixture seedOther(ProjectRole role) {
        return seedFixture(new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate()), role);
    }
    /** 只播种身份与合法类型，域及包必须由HTTP创建。 */
    private Fixture seedFixture(Fixture fixture, ProjectRole role) {
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

    /** 每例全部归属，确保失败后也能精确清理。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
