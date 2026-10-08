package com.things.link.bootstrap.fixture;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Board;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.schema.DefaultDashboardPublicationCandidateFactory;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardSchemaCanonicalizer;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 数据读取真实HTTP夹具：追加规范版本而不修改不可变历史，不借D-145未实现发布资格建立运行事实。 */
public final class WebAppDataRuntimeFixture {
    /** JSON仅用于夹具构造，版本摘要仍从真实PG取得。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 不允许持有跨测试的可变fixture状态。 */
    private WebAppDataRuntimeFixture() { }

    /** 建立一个包含五种读取声明、一个模型及两台绑定设备的当前版本。 */
    public static DataFixture seed(JdbcTemplate owner) {
        // 包含基础夹具的整个造数阶段只取得一个连接，保留调用方事务与连接关闭职责。
        return owner.execute((ConnectionCallback<DataFixture>) connection -> seedOnConnection(
                new JdbcTemplate(new SingleConnectionDataSource(connection, true))));
    }

    /** 与基础夹具嵌套复用同一真实连接，不修改生产数据源。 */
    private static DataFixture seedOnConnection(JdbcTemplate owner) {
        Fixture base = WebAppRuntimeFixture.seed(owner);
        UUID type = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind)
                VALUES (?,?,?,?,'运行测试模型','STANDARD','DIRECT')
                """, type, base.tenantId(), base.projectId(), "type_" + type.toString().replace("-", ""));
        String modelJson = """
                {"properties":{"temperature":{"dataType":"NUMBER","unit":"℃","minimum":-50,"maximum":150},
                "secret":{"dataType":"TEXT"}},"events":{},"commands":{}}
                """;
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,
                    schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                """, model, base.tenantId(), base.projectId(), type, modelJson, modelJson);
        String digest = owner.queryForObject("SELECT schema_digest FROM dev_thing_model_version WHERE id=?", String.class, model);
        ObjectNode schema = schema(model, digest);
        var candidate = new DefaultDashboardPublicationCandidateFactory(new JacksonDashboardSchemaParser(),
                new JdbcDashboardSchemaCanonicalizer(owner)).prepare(new DashboardDraft(base.authorized().dashboardId(),
                base.tenantId(), base.projectId(), schema, 0, base.actorId(), Instant.now(), Instant.now(),
                List.of(new DashboardModelReference(0, "weather", model))));
        Fixture runtime = appendRuntimeVersion(owner, base, candidate, model);
        UUID first = addDevice(owner, runtime, type, model, true, Instant.parse("2026-09-06T12:00:02Z"));
        UUID second = addDevice(owner, runtime, type, model, true, Instant.parse("2026-09-06T12:00:01Z"));
        return new DataFixture(runtime, type, model, digest, first, second);
    }

    /** 目录反例可交错插入未绑定、删除或其他模型设备；只有绑定选择决定App可见性。 */
    public static UUID addDevice(JdbcTemplate owner, Fixture f, UUID type, UUID model, boolean bound, Instant createdAt) {
        UUID device = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,
                    thing_model_version_id,status,created_at)
                VALUES (?,?,?,?,?,'目录温度设备',?,'ONLINE',?)
                """, device, f.tenantId(), f.projectId(), type, "device_" + device.toString().replace("-", ""),
                model, Timestamp.from(createdAt));
        if (bound) owner.update("""
                INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role)
                VALUES (?,?,?,?,?,'READ_ONLY')
                """, UUID.randomUUID(), f.tenantId(), f.projectId(), f.appUserId(), device);
        return device;
    }

    /** 五种源均显式声明；未声明secret属性用于验证有效上下文不能读取任意键。 */
    private static ObjectNode schema(UUID model, String digest) {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        root.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        root.putArray("models").addObject().put("key", "weather").put("versionId", model.toString())
                .put("digestAlgorithm", "PG_JSONB_TEXT_V1_SHA256").put("digest", digest).put("profile", "TC_PROPERTY_COMPOSITE_V1");
        var variables = root.putArray("variables");
        variables.addObject().put("key", "sensor").put("type", "DEVICE_SINGLE").put("title", "当前设备").put("modelKey", "weather");
        variables.addObject().put("key", "sensors").put("type", "DEVICE_MULTI").put("title", "设备集合")
                .put("modelKey", "weather").put("maxItems", 10);
        variables.addObject().put("key", "range").put("type", "TIME_RANGE").put("title", "时段");
        var components = root.putArray("pages").addObject().put("id", "main").put("title", "授权看板").putArray("components");
        ObjectNode value = component("value", "VALUE_CARD", 0);
        value.withObject("/bindings").putObject("value").put("source", "CURRENT_VALUE").put("propertyKey", "temperature")
                .putObject("device").put("variableKey", "sensor");
        components.add(value);
        ObjectNode selector = component("selector", "DEVICE_SELECTOR", 1);
        selector.withObject("/bindings").putObject("directory").put("source", "DEVICE_DIRECTORY").put("variableKey", "sensors");
        components.add(selector);
        ObjectNode chart = component("chart", "LINE_CHART", 2);
        chart.withObject("/props").putArray("series").addObject().put("id", "temperature_series").put("label", "温度");
        ObjectNode history = chart.withObject("/bindings").putArray("series").addObject().put("id", "temperature_series")
                .putObject("value").put("source", "HISTORY_SERIES").put("propertyKey", "temperature")
                .put("timeRangeVariableKey", "range").put("granularity", "RAW").put("aggregation", "AVG");
        history.putObject("device").put("variableKey", "sensor");
        components.add(chart);
        ObjectNode alarms = component("alarms", "ALARM_LIST", 3);
        ObjectNode binding = alarms.withObject("/bindings").putObject("alarms").put("source", "ALARM_LIST");
        binding.putObject("devices").put("variableKey", "sensors");
        binding.putArray("conditionStates").add("ACTIVE");
        binding.putArray("ackStates").add("UNACKNOWLEDGED");
        binding.putArray("severities").add("WARNING");
        components.add(alarms);
        return root;
    }

    /** 不重叠的一行一组件，默认值由真实Schema管线统一注入。 */
    private static ObjectNode component(String id, String kind, int y) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("kind", kind).put("componentVersion", "1.0.0");
        node.putObject("layout").put("x", 0).put("y", y).put("w", 24).put("h", 1);
        node.putObject("props");
        node.putObject("bindings");
        return node;
    }

    /** 完整追加看板V2/应用V2以及派生关系；不禁用不可变约束或伪造旧摘要。 */
    private static Fixture appendRuntimeVersion(JdbcTemplate owner, Fixture base,
            DashboardPublicationCandidate candidate, UUID model) {
        UUID dashboardVersion = UUID.randomUUID();
        UUID applicationVersion = UUID.randomUUID();
        String required = JSON.writeValueAsString(candidate.requiredComponents());
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,
                    source_draft_revision,schema,schema_version,schema_digest_algorithm,schema_digest,
                    required_components,required_resources,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',?,?::jsonb,'[]',?,now())
                """, dashboardVersion, base.tenantId(), base.projectId(), base.authorized().dashboardId(),
                candidate.normalizedSchema().toString(), candidate.schemaDigest(), required, base.actorId());
        owner.update("""
                INSERT INTO dash_dashboard_version_model_ref(tenant_id,project_id,dashboard_id,dashboard_version_id,
                    position,model_key,thing_model_version_id) VALUES (?,?,?,?,0,'weather',?)
                """, base.tenantId(), base.projectId(), base.authorized().dashboardId(), dashboardVersion, model);
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=2 WHERE id=?",
                dashboardVersion, base.authorized().dashboardId());
        ObjectNode snapshot = (ObjectNode) JSON.readTree(owner.queryForObject(
                "SELECT snapshot::text FROM app_application_version WHERE id=?", String.class, base.applicationVersionId()));
        ObjectNode reference = (ObjectNode) snapshot.path("dashboardRefs").get(1);
        reference.put("dashboardVersionId", dashboardVersion.toString()).put("dashboardVersionNumber", "2")
                .put("schemaDigest", candidate.schemaDigest());
        snapshot.set("requiredComponents", JSON.readTree(required));
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,
                    source_draft_revision,snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,2,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?,now())
                """, applicationVersion, base.tenantId(), base.projectId(), base.applicationId(), snapshot.toString(),
                snapshot.toString(), base.actorId());
        owner.update("""
                INSERT INTO app_application_version_dashboard_ref(tenant_id,project_id,application_id,
                    application_version_id,position,dashboard_id,dashboard_version_id) VALUES (?,?,?,?,0,?,?),(?,?,?,?,1,?,?)
                """, base.tenantId(), base.projectId(), base.applicationId(), applicationVersion,
                base.entry().dashboardId(), base.entry().dashboardVersionId(), base.tenantId(), base.projectId(),
                base.applicationId(), applicationVersion, base.authorized().dashboardId(), dashboardVersion);
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?",
                applicationVersion, base.applicationId());
        return new Fixture(base.tenantId(), base.projectId(), base.appUserId(), base.actorId(), base.applicationId(),
                applicationVersion, base.appKey(), base.entry(), new Board(base.authorized().dashboardId(), dashboardVersion, "授权看板"));
    }

    /** @param runtime 真实运行身份 @param type 类型 @param model 模型版本 @param digest PG摘要 @param first 首台设备 @param second 次台设备 */
    public record DataFixture(Fixture runtime, UUID type, UUID model, String digest, UUID first, UUID second) { }
}
