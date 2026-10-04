package com.things.link.bootstrap.fixture;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.dashboard.application.schema.DefaultDashboardPublicationCandidateFactory;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardSchemaCanonicalizer;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 生产约束下合法静态历史夹具，只服务真实读取验收，不授予生产Host/D-145发布资格。 */
public final class WebAppStaticCanvasFixture {
    /** 纯夹具不得持有跨测试身份状态。 */
    private WebAppStaticCanvasFixture() { }
    /**
     * @param owner 独占测试库播种连接
     * @param encodedPassword 生产编码器生成的测试用户口令摘要
     * @param mode 合同内画布模式
     * @param unsupported 是否使用合法动态绑定并要求当前1.0.0宿主之外的版本范围
     * @return 已发布应用及真实用户父事实，V2通过current指针读取
     */
    public static Fixture seed(JdbcTemplate owner, String encodedPassword, String mode, boolean unsupported) {
        Fixture fixture = WebAppRuntimeFixture.seed(owner);
        owner.update("UPDATE app_user SET password_hash=? WHERE id=?", encodedPassword, fixture.appUserId());
        WebAppRuntimeFixture.grant(owner, fixture, fixture.entry());
        appendStaticVersion(owner, fixture, mode, unsupported);
        return fixture;
    }

    /**
     * 真实规范化器派生完整清单后追加不可变V2，不修改既有V1；动态绑定已由S12-3i支持，较新宿主范围继续提供整版拒绝反例。
     * 这些owner播种只证明读取/渲染，不宣称生产Host允许该应用通过发布。
     */
    private static void appendStaticVersion(JdbcTemplate owner, Fixture f, String mode, boolean unsupported) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode schema = json.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", mode);
        schema.putArray("models");
        var variables = schema.putArray("variables");
        var pages = schema.putArray("pages");
        var firstPage = pages.addObject().put("id", "main").put("title", "静态首页").putArray("components");
        boolean fixed = "FIXED_SCREEN".equals(mode);
        ObjectNode text = staticComponent(json, "safe_text", "TEXT", fixed ? 0 : 12, 0, fixed ? 960 : 12, fixed ? 240 : 12);
        text.withObject("/props").put("content", "字面恶意文本：<img src=x onerror=alert(1)><script>alert(2)</script> 中文保持纯文本");
        if (unsupported) {
            variables.addObject().put("key", "choice").put("type", "TEXT_ENUM").put("title", "合法动态枚举")
                    .putArray("options").addObject().put("value", "first").put("label", "非静态合法内容");
            text.withObject("/props").remove("content");
            text.withObject("/bindings").putObject("text").put("source", "ENUM_TEXT").put("variableKey", "choice");
        }
        firstPage.add(text);
        ObjectNode image = staticComponent(json, "mark", "IMAGE", fixed ? 960 : 0, 0, fixed ? 960 : 12, fixed ? 540 : 12);
        image.withObject("/props").put("resourceId", "device_mark")
                .put("resourceDigest", "5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa")
                .put("alt", "设备标记");
        firstPage.add(image);
        if (!fixed) {
            ObjectNode next = staticComponent(json, "next_text", "TEXT", 0, 0, 24, 8);
            next.withObject("/props").put("content", "第二页静态内容");
            pages.addObject().put("id", "next").put("title", "第二页").putArray("components").add(next);
        }
        var candidate = new DefaultDashboardPublicationCandidateFactory(new JacksonDashboardSchemaParser(),
                new JdbcDashboardSchemaCanonicalizer(owner)).prepare(new DashboardDraft(f.entry().dashboardId(),
                f.tenantId(), f.projectId(), schema, 0, f.actorId(), Instant.now(), Instant.now(), List.of()));
        UUID dashboardVersion = UUID.randomUUID();
        UUID applicationVersion = UUID.randomUUID();
        String components = json.writeValueAsString(candidate.requiredComponents());
        String resources = json.writeValueAsString(candidate.requiredResources());
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,
                    source_draft_revision,schema,schema_version,schema_digest_algorithm,schema_digest,
                    required_components,required_resources,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',?,?::jsonb,?::jsonb,?,now())
                """, dashboardVersion, f.tenantId(), f.projectId(), f.entry().dashboardId(),
                candidate.normalizedSchema().toString(), candidate.schemaDigest(), components, resources, f.actorId());
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=2 WHERE id=?", dashboardVersion, f.entry().dashboardId());
        ObjectNode snapshot = (ObjectNode) json.readTree(owner.queryForObject("SELECT snapshot::text FROM app_application_version WHERE id=?",
                String.class, f.applicationVersionId()));
        // S12-3j：不能把已支持的ENUM_TEXT继续当失败输入；合法未来宿主范围必须整版拒绝。
        if (unsupported) snapshot.putObject("hostCompatibility")
                .put("minInclusive", "2.0.0").put("maxExclusive", "3.0.0");
        ObjectNode reference = (ObjectNode) snapshot.path("dashboardRefs").get(0).deepCopy();
        reference.put("dashboardVersionId", dashboardVersion.toString()).put("dashboardVersionNumber", "2")
                .put("schemaDigest", candidate.schemaDigest());
        var pageRefs = reference.putArray("pages");
        for (var page : candidate.normalizedSchema().path("pages")) pageRefs.addObject()
                .put("id", page.path("id").asString()).put("title", page.path("title").asString());
        snapshot.putArray("dashboardRefs").add(reference);
        snapshot.set("requiredComponents", json.readTree(components));
        snapshot.set("requiredResources", json.readTree(resources));
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,
                    source_draft_revision,snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?,now())
                """, applicationVersion, f.tenantId(), f.projectId(), f.applicationId(), snapshot.toString(), snapshot.toString(), f.actorId());
        owner.update("""
                INSERT INTO app_application_version_dashboard_ref(tenant_id,project_id,application_id,
                    application_version_id,position,dashboard_id,dashboard_version_id) VALUES (?,?,?,?,0,?,?)
                """, f.tenantId(), f.projectId(), f.applicationId(), applicationVersion, f.entry().dashboardId(), dashboardVersion);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", applicationVersion, f.applicationId());
    }

    /** 两种布局保持逻辑矩形不重叠；组件版本沿冻结合同唯一1.0.0。 */
    private static ObjectNode staticComponent(ObjectMapper json, String id, String kind, int x, int y, int width, int height) {
        ObjectNode component = json.createObjectNode().put("id", id).put("kind", kind).put("componentVersion", "1.0.0");
        component.putObject("layout").put("x", x).put("y", y).put("w", width).put("h", height);
        component.putObject("props"); component.putObject("bindings"); return component;
    }

}
