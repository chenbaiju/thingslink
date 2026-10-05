package com.things.link.bootstrap.assistant;

import com.things.link.alarm.application.ConsoleDeviceAlarmStatusService;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** AG-1a：真实Console签名、APP数据库角色及各域新事务，不用模型桩替代权限。 */
@AutoConfigureMockMvc
class DeviceEvidenceApiTests extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired AppTokenIssuer appTokens;
    @Autowired JdbcTemplate application;
    @MockitoSpyBean ConsoleDeviceAlarmStatusService alarms;
    final ObjectMapper json = new ObjectMapper();
    JdbcTemplate owner;
    DataFixture data;
    String token;

    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        data = WebAppDataRuntimeFixture.seed(owner);
        var f = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
    }
    MockHttpServletRequestBuilder request() {
        return get(path(data.runtime().projectId(), data.first()))
                .queryParam("expectedModelVersionId", data.model().toString()).queryParam("propertyKey", "temperature")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    String path(UUID project, UUID device) { return "/api/v1/projects/" + project + "/assistant/devices/" + device + "/snapshot"; }
    JsonNode success(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return json.readTree(r.getResponse().getContentAsString());
    }
    void status(MockHttpServletRequestBuilder call, int expected) throws Exception {
        var r = mvc.perform(call).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }
    @Test void readsAsAllRolesWithSparseEvidenceAndNoPrivateDeviceFields() throws Exception {
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR", "VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, data.runtime().projectId(), data.runtime().actorId());
            var result = success(mvc.perform(request()).andReturn());
            assertThat(result.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "projectId", "deviceId", "modelVersionId",
                    "collectionStartedAt", "collectionFinishedAt", "device", "properties", "alarmSummary");
            assertThat(result.path("device").propertyNames()).containsExactlyInAnyOrder("status", "lastOnlineAt", "readAt");
            assertThat(result.path("properties").get(0).path("availability").asString()).isEqualTo("MISSING");
            assertThat(result.path("alarmSummary").path("state").asString()).isEqualTo("NORMAL");
        }
    }
    @Test void preservesPgValueAndNeverExposesUnknownSourceValue() throws Exception {
        var f = data.runtime();
        owner.update("""
                INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version,
                    reported_sequence,reported_revisions)
                VALUES (?,?,?,'{"temperature":23.5}','{"temperature":"2026-10-03T00:00:00Z"}',?::jsonb,1,'{"temperature":"1"}')
                """, data.first(), f.tenantId(), f.projectId(), "{\"temperature\":\"" + data.model() + "\"}");
        var value = success(mvc.perform(request()).andReturn()).path("properties").get(0);
        assertThat(value.path("value").asDouble()).isEqualTo(23.5);
        assertThat(value.path("reportedRevision").asString()).isEqualTo("1");
        owner.update("UPDATE dev_shadow SET reported_model_version='{}',reported_sequence=2,reported_revisions='{\"temperature\":\"2\"}' WHERE device_id=?", data.first());
        value = success(mvc.perform(request()).andReturn()).path("properties").get(0);
        assertThat(value.path("availability").asString()).isEqualTo("SOURCE_UNKNOWN");
        assertThat(value.path("value").isNull()).isTrue();
        owner.update("UPDATE dev_shadow SET reported_model_version=?::jsonb,reported_sequence=3,reported_revisions='{\"temperature\":\"3\"}' WHERE device_id=?",
                "{\"temperature\":\"" + UUID.randomUUID() + "\"}", data.first());
        value = success(mvc.perform(request()).andReturn()).path("properties").get(0);
        assertThat(value.path("availability").asString()).isEqualTo("MODEL_MISMATCH");
        assertThat(value.path("value").isNull()).isTrue();
    }
    @Test void rejectsUnboundedUnknownAndDuplicateParameters() throws Exception {
        status(request().queryParam("unknown", "x"), 400);
        status(request().queryParam("expectedModelVersionId", data.model().toString()), 400);
        status(request().queryParam("propertyKey", "temperature"), 400);
        status(request().queryParam("propertyKey", "unknown"), 400);
        status(request().queryParam("propertyKey", "temperature,secret"), 400);
        status(request().queryParam("propertyKey", java.util.stream.IntStream.range(0, 10).mapToObj(i -> "p" + i).toArray(String[]::new)), 400);
    }
    @Test void rejectsWrongIdentityAndRevokedOrStaleToken() throws Exception {
        var f = data.runtime();
        status(get(path(f.projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString()).queryParam("propertyKey", "temperature"), 401);
        String app = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        token = app; status(request(), 401);
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), null)).value();
        status(request(), 404);
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId(), 999)).value();
        status(request(), 401);
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", f.projectId(), f.actorId());
        status(request(), 401);
    }
    @Test void collaboratorUsesOwningTenantAndForeignDeviceIsInvisible() throws Exception {
        UUID tenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'协作者')", tenant);
        token = tokens.issue(new AuthenticatedPrincipal(data.runtime().actorId(), tenant, data.runtime().projectId())).value();
        success(mvc.perform(request()).andReturn());
        var foreign = WebAppDataRuntimeFixture.seed(owner);
        status(get(path(data.runtime().projectId(), foreign.first())).queryParam("expectedModelVersionId", foreign.model().toString())
                .queryParam("propertyKey", "temperature").header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
        status(get(path(foreign.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString())
                .queryParam("propertyKey", "temperature").header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
    }
    @Test void rejectsModelDriftBeforeAndAfterSampling() throws Exception {
        status(get(path(data.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", UUID.randomUUID().toString())
                .queryParam("propertyKey", "temperature").header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 409);
        UUID next = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                SELECT ?,tenant_id,project_id,device_type_id,'1.1.0',1,1,0,'MINOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
                  FROM dev_thing_model_version WHERE id=?
                """, next, data.model());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?", next, data.first());
            return result;
        }).when(alarms).read(eq(data.runtime().projectId()), anyList());
        status(request(), 409);
    }
    @Test void finalNewTransactionSeesMembershipRemovedAfterAlarmRead() throws Exception {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", data.runtime().projectId(), data.runtime().actorId());
            return result;
        }).when(alarms).read(eq(data.runtime().projectId()), anyList());
        status(request(), 404);
    }
    @Test void activeAlarmRemainsActiveAfterAcknowledgement() throws Exception {
        var f = data.runtime();
        UUID rule = UUID.randomUUID();
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,
                    property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,?,?,?,'temperature','GT',30,'LT',25,'WARNING')
                """, rule, f.tenantId(), f.projectId(), rule.toString(), rule.toString(), data.first());
        owner.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                    alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,
                    acknowledged_at,acknowledged_by)
                VALUES (?,?,?,?,'DEVICE',?,?,'WARNING','ACTIVE','ACKNOWLEDGED',now(),now(),now(),31,now(),?)
                """, UUID.randomUUID(), f.tenantId(), f.projectId(), rule, data.first(), rule.toString(), f.actorId());
        assertThat(success(mvc.perform(request()).andReturn()).path("alarmSummary").path("state").asString()).isEqualTo("ACTIVE");
    }
    @Test void unboundDeviceCannotMasqueradeAsExpectedPublishedModel() throws Exception {
        var f = data.runtime();
        UUID type = UUID.randomUUID();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind)"
                + " VALUES (?,?,?,?,'未发布','STANDARD','DIRECT')", type, f.tenantId(), f.projectId(), "type_" + type.toString().replace("-", ""));
        UUID unbound = WebAppDataRuntimeFixture.addDevice(owner, f, type, null, false, java.time.Instant.now());
        status(get(path(f.projectId(), unbound)).queryParam("expectedModelVersionId", data.model().toString())
                .queryParam("propertyKey", "temperature").header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 409);
    }
    @Test void sourceFailureAndDeletedDeviceCannotBecomeNormal() throws Exception {
        doThrow(new IllegalStateException("test source failure")).when(alarms).read(eq(data.runtime().projectId()), anyList());
        status(request(), 500);
        reset(alarms);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", data.first());
        status(request(), 404);
    }
}
