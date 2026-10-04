package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 生产默认无受控signer时真实认证HTTP失败关闭，不建立发布尝试。 */
@AutoConfigureMockMvc
class OtaPublicationUnavailableHttpTests extends AbstractIntegrationTest {
    /** 只解析真实HTTP结果，不生成成功响应。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 本例所有身份，用于失败后精确清理。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 完整Console认证与幂等链，JSON入口无需模拟流式Servlet。 */
    @Autowired private MockMvc mvc;
    /** 生产令牌签发器，仍由实际安全链验签和数据库确权。 */
    @Autowired private TokenIssuer tokens;
    /** 直接断言生产默认没有受控签名器，不能用返回失败的替身代替缺配置。 */
    @Autowired private org.springframework.beans.factory.ObjectProvider<com.things.link.ota.application.OtaControlledReleaseSigner> signers;
    /** 不装配测试签名器；无适配器拒绝先于任何持久尝试创建。 */
    @Test
    void absentSignerRejectsWithoutCreatingPublicationOrChangingDraft() throws Exception {
        assertThat(signers.getIfAvailable()).isNull();
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode firmware = ok(write(f, base(f), UUID.randomUUID().toString(), body(f, "no-signer")));
        String path = base(f) + "/" + firmware.path("id").asText() + "/publications";
        String request = "{\"expectedRevision\":\"0\",\"uploadSessionId\":\"" + UUID.randomUUID()
                + "\",\"manifest\":{}}";
        error(write(f, path, UUID.randomUUID().toString(), request), 503, 70016);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_firmware_publication WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(ok(read(f, base(f) + "/" + firmware.path("id").asText())).path("status").asText()).isEqualTo("DRAFT");
        assertThat(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        error(write(f, path, UUID.randomUUID().toString(), request), 403, 70020);
    }
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

    /** 每例全部归属，确保失败后也能精确清理。
     * @param tenantId 独占租户
     * @param projectId 独占项目
     * @param accountId 当前请求账号
     * @param ownerId 项目保留所有者
     * @param typeId 已发布类型
     * @param modelId 不可变模型版本
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
