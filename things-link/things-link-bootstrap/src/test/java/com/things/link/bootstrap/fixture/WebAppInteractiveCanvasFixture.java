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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 数据读取真实HTTP夹具：追加规范版本而不修改不可变历史，不借D-145未实现发布资格建立运行事实。 */
public final class WebAppInteractiveCanvasFixture {
    /** JSON仅用于夹具构造，版本摘要仍从真实PG取得。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 不允许持有跨测试的可变fixture状态。 */
    private WebAppInteractiveCanvasFixture() { }

    /** 建立专属当前画布历史、真实模型和三台不同排序设备；不代表D-145生产Host资格。 */
    public static DataFixture seed(JdbcTemplate owner, String encodedPassword) {
        return seed(owner, encodedPassword, false);
    }

    /** 前台告警旅程另增加纯告警页，保留原交互夹具和真实规范版本生成链。 */
    public static DataFixture seedForegroundAlarms(JdbcTemplate owner, String encodedPassword) {
        return seed(owner, encodedPassword, true);
    }

    /** 同一候选可同时验证混合与纯告警，不修改既有不可变版本。 */
    private static DataFixture seed(JdbcTemplate owner, String encodedPassword, boolean foregroundAlarms) {
        Fixture base = WebAppRuntimeFixture.seed(owner);
        owner.update("UPDATE app_user SET password_hash=? WHERE id=?", encodedPassword, base.appUserId());
        WebAppRuntimeFixture.grant(owner, base, base.entry());
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
        if (foregroundAlarms) {
            var source = schema.get("pages").get(0).get("components");
            var pure = ((tools.jackson.databind.node.ArrayNode) schema.get("pages")).addObject()
                    .put("id", "alarms_only").put("title", "前台告警").putArray("components");
            for (var node : source) {
                if (node.get("kind").asString().equals("ALARM_LIST") || node.get("kind").asString().equals("DEVICE_SELECTOR")) {
                    ObjectNode copy = (ObjectNode) node.deepCopy();
                    copy.put("id", "pure_" + node.get("id").asString());
                    copy.withObject("/layout").put("x", 0).put("y", pure.size() * 16).put("w", 24).put("h", 16);
                    pure.add(copy);
                }
            }
        }
        var candidate = new DefaultDashboardPublicationCandidateFactory(new JacksonDashboardSchemaParser(),
                new JdbcDashboardSchemaCanonicalizer(owner)).prepare(new DashboardDraft(base.authorized().dashboardId(),
                base.tenantId(), base.projectId(), schema, 0, base.actorId(), Instant.now(), Instant.now(),
                List.of(new DashboardModelReference(0, "weather", model))));
        Fixture runtime = appendRuntimeVersion(owner, base, candidate, model);
        UUID first = addDevice(owner, runtime, type, model, true, Instant.parse("2026-09-06T12:00:02Z"));
        UUID second = addDevice(owner, runtime, type, model, true, Instant.parse("2026-09-06T12:00:01Z"));
        addDevice(owner, runtime, type, model, true, Instant.parse("2026-09-06T12:00:00Z"));
        owner.update("UPDATE dev_device SET name='温度甲',status='ONLINE' WHERE id=?", first);
        owner.update("UPDATE dev_device SET name='温度乙',status='OFFLINE' WHERE id=?", second);
        shadow(owner, runtime, model, first, "12.5");
        shadow(owner, runtime, model, second, "78.25");
        interactiveFacts(owner, runtime, model, first, second);
        return new DataFixture(runtime, type, model, digest, first, second);
    }

    /** 历史写生产内部事实表后刷新真实连续聚合；不写物化chunk或伪造成功HTTP。 */
    private static void interactiveFacts(JdbcTemplate owner, Fixture f, UUID model, UUID first, UUID second) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        UUID oldNumeric = historicalModel(owner, model, "0.9.0", "NUMBER");
        UUID oldText = historicalModel(owner, model, "0.8.0", "TEXT");
        for (int minute : List.of(20, 19)) owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_double)
                VALUES (?,?,'temperature',?,gen_random_uuid(),'NUMBER',?,'0.9.0',18)
                """, f.projectId(), first, Timestamp.from(now.minusSeconds(minute * 60L)), oldNumeric);
        for (int minute : List.of(12, 11, 8, 7)) {
            owner.update("""
                    INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                        data_type,thing_model_version_id,model_version,value_double)
                    VALUES (?,?,'temperature',?,gen_random_uuid(),'NUMBER',?,'1.0.0',?)
                    """, f.projectId(), first, Timestamp.from(now.minusSeconds(minute * 60L)), model, 20 + minute);
        }
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double)"
                + " VALUES (?,?,'temperature',?,gen_random_uuid(),9)", f.projectId(), first, Timestamp.from(now.minusSeconds(900)));
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_text)
                VALUES (?,?,'temperature',?,gen_random_uuid(),'TEXT',?,'0.8.0','历史非数值')
                """, f.projectId(), second, Timestamp.from(now.minusSeconds(600)), oldText);
        // 三层聚合均采用UTC桶；先覆盖最早点所在完整日，避免日层向内对齐后得到空窗口。
        Instant refreshFrom = now.minusSeconds(20 * 60L).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant refreshTo = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(86400);
        for (String aggregate : List.of("ts_property_point_1m_internal", "ts_property_point_1h_internal", "ts_property_point_1d_internal")) {
            owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz)",
                    aggregate, Timestamp.from(refreshFrom), Timestamp.from(refreshTo));
        }
        UUID rule = UUID.randomUUID();
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                    trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,'真实历史告警','TEMPERATURE',?,'temperature','GT',30,'LT',20,'WARNING')
                """, rule, f.tenantId(), f.projectId(), first);
        for (int index = 0; index < 5; index++) {
            String condition = index == 2 ? "PENDING" : "ACTIVE";
            String severity = index == 3 ? "MAJOR" : "WARNING";
            owner.update("""
                    INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                        alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,
                        created_at,updated_at,acknowledged_at,acknowledged_by)
                    VALUES (?,?,?,?,'DEVICE',?,?,?,?,?,?,?,?,31,?,?,?,?)
                    """, UUID.randomUUID(), f.tenantId(), f.projectId(), rule, first, "交互告警_" + index,
                    severity, condition, index == 4 ? "ACKNOWLEDGED" : "UNACKNOWLEDGED", Timestamp.from(now), index == 2 ? null : Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now.minusSeconds(index)), Timestamp.from(now.minusSeconds(index)),
                    index == 4 ? Timestamp.from(now) : null, index == 4 ? f.actorId() : null);
        }
    }

    /** 历史点拥有真实不可变旧模型父行，不能把TEXT事实伪装成当前NUMBER版本。 */
    private static UUID historicalModel(JdbcTemplate owner, UUID current, String version, String type) {
        UUID id = UUID.randomUUID();
        String snapshot = "{\"properties\":{\"temperature\":{\"dataType\":\"" + type + "\"}},\"events\":{},\"commands\":{}}";
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                SELECT ?,tenant_id,project_id,device_type_id,?,0,?,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256'
                FROM dev_thing_model_version WHERE id=?
                """, id, version, version.equals("0.9.0") ? 9 : 8, snapshot, snapshot, current);
        return id;
    }

    /** 三个稀疏映射来自同一PG影子行，数值不由浏览器脚本伪造。 */
    private static void shadow(JdbcTemplate owner, Fixture f, UUID model, UUID device, String value) {
        owner.update("""
                INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version)
                VALUES (?,?,?,?::jsonb,'{"temperature":"2026-09-06T12:00:00.123456Z"}',?::jsonb)
                """, device, f.tenantId(), f.projectId(), "{\"temperature\":" + value + "}",
                "{\"temperature\":\"" + model + "\"}");
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

    /** 仅声明单设备目录、当前值与状态，不借未实现的历史或复合组件放宽宿主资格。 */
    private static ObjectNode schema(UUID model, String digest) {
        ObjectNode root = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        root.putObject("presentation").put("mode", "RESPONSIVE_GRID");
        root.putArray("models").addObject().put("key", "weather").put("versionId", model.toString())
                .put("digestAlgorithm", "PG_JSONB_TEXT_V1_SHA256").put("digest", digest).put("profile", "TC_PROPERTY_COMPOSITE_V1");
        var variables = root.putArray("variables");
        variables.addObject().put("key", "sensor").put("type", "DEVICE_SINGLE").put("title", "当前设备").put("modelKey", "weather");
        variables.addObject().put("key", "range").put("type", "TIME_RANGE").put("title", "历史时段");
        var caption = variables.addObject().put("key", "caption").put("type", "TEXT_ENUM").put("title", "文字说明");
        caption.putArray("options").addObject().put("value", "safe").put("label", "中文<img src=x onerror=alert(1)>");
        var components = root.putArray("pages").addObject().put("id", "main").put("title", "授权看板").putArray("components");
        ObjectNode value = component("value", "VALUE_CARD", 0);
        value.withObject("/bindings").putObject("value").put("source", "CURRENT_VALUE").put("propertyKey", "temperature")
                .putObject("device").put("variableKey", "sensor");
        components.add(value);
        ObjectNode selector = component("selector", "DEVICE_SELECTOR", 1);
        selector.withObject("/props").put("pageSize", 2);
        selector.withObject("/bindings").putObject("directory").put("source", "DEVICE_DIRECTORY").put("variableKey", "sensor");
        components.add(selector);
        ObjectNode chart = component("chart", "LINE_CHART", 2);
        chart.withObject("/props").putArray("series").addObject().put("id", "temperature_series").put("label", "温度历史");
        var series = chart.withObject("/bindings").putArray("series").addObject().put("id", "temperature_series")
                .putObject("value").put("source", "HISTORY_SERIES").put("propertyKey", "temperature")
                .put("timeRangeVariableKey", "range").put("granularity", "ONE_MINUTE").put("aggregation", "AVG");
        series.putObject("device").put("variableKey", "sensor");
        components.add(chart);
        ObjectNode alarms = component("alarms", "ALARM_LIST", 3);
        alarms.withObject("/props").put("pageSize", 1);
        var binding = alarms.withObject("/bindings").putObject("alarms").put("source", "ALARM_LIST");
        binding.putObject("devices").put("variableKey", "sensor");
        binding.putArray("conditionStates").add("ACTIVE");
        binding.putArray("ackStates").add("UNACKNOWLEDGED");
        binding.putArray("severities").add("WARNING");
        components.add(alarms);
        ObjectNode text = component("text", "TEXT", 4);
        text.withObject("/bindings").putObject("text").put("source", "ENUM_TEXT").put("variableKey", "caption");
        components.add(text);
        return root;
    }

    /** 不重叠的双列可读画布，默认值由真实Schema管线统一注入。 */
    private static ObjectNode component(String id, String kind, int y) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("kind", kind).put("componentVersion", "1.0.0");
        node.putObject("layout").put("x", (y % 2) * 12).put("y", (y / 2) * 16).put("w", 12).put("h", 16);
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
        // 新应用V2明确以本片设备画布为入口；旧V1入口与原授权事实保持不可变。
        snapshot.put("entryDashboardId", base.authorized().dashboardId().toString());
        ObjectNode reference = (ObjectNode) snapshot.path("dashboardRefs").get(1);
        reference.put("dashboardVersionId", dashboardVersion.toString()).put("dashboardVersionNumber", "2")
                .put("schemaDigest", candidate.schemaDigest());
        // 应用快照封存精确页面导航；新增纯告警页不能只更新看板版本ID和摘要。
        var pages = reference.putArray("pages");
        for (var page : candidate.normalizedSchema().get("pages")) {
            pages.addObject().put("id", page.get("id").asString()).put("title", page.get("title").asString());
        }
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
