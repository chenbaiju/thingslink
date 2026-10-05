package com.things.link.bootstrap.assistant;

import com.things.link.alarm.application.ConsoleAlarmDeviceQueryService;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class AlarmEvidenceApiTests extends AbstractIntegrationTest {
    @Autowired MockMvc mvc; @Autowired TokenIssuer tokens; @Autowired JdbcTemplate application;
    @MockitoSpyBean ConsoleAlarmDeviceQueryService alarms;
    JdbcTemplate owner; DataFixture data; String token;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        data = WebAppDataRuntimeFixture.seed(owner); var f = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
    }
    String path(UUID project, UUID device) { return "/api/v1/projects/" + project + "/assistant/devices/" + device + "/alarms"; }
    MockHttpServletRequestBuilder request() {
        return get(path(data.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    void status(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
    }
    UUID incident(UUID device, Instant at) {
        var f = data.runtime(); UUID rule = UUID.randomUUID(), id = UUID.randomUUID();
        owner.update("""
            INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
            VALUES (?,?,?,?,?,?,'temperature','GT',30,'LT',25,'WARNING')
            """, rule, f.tenantId(), f.projectId(), "不回传规则_" + id, "禁止回传自由文本_" + id, device);
        owner.update("""
            INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
                condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,created_at,updated_at)
            VALUES (?,?,?,?,'DEVICE',?,?,'WARNING','ACTIVE','UNACKNOWLEDGED',?,?,?,31,?,?)
            """, id, f.tenantId(), f.projectId(), rule, device, "禁止回传自由文本_" + id, Timestamp.from(at), Timestamp.from(at),
            Timestamp.from(at), Timestamp.from(at), Timestamp.from(at)); return id;
    }
    @Test void fourRolesReadNoStoreClosedFactsAndForeignDeviceCannotOccupyPage() throws Exception {
        var at = Instant.parse("2026-10-04T00:00:00Z"); UUID wanted = incident(data.first(), at);
        incident(data.second(), at.plusSeconds(1));
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR", "VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, data.runtime().projectId(), data.runtime().actorId());
            var result = mvc.perform(request().queryParam("limit", "1")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            var body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("sourceModelState").asString()).isEqualTo("NOT_PROVIDED");
            assertThat(body.path("hasMore").asBoolean()).isFalse();
            assertThat(body.path("items").size()).isEqualTo(1);
            assertThat(body.path("items").get(0).path("id").asString()).isEqualTo(wanted.toString());
            assertThat(body.path("items").get(0).path("clearedAt").isNull()).isTrue();
            assertThat(body.toString()).doesNotContain("禁止回传", "alarmType", "ruleId", "lastValue", "tenantId", "apiKey");
        }
    }
    @Test void cursorPaginatesAndCannotChangeActorDeviceLimitOrBytes() throws Exception {
        var at = Instant.parse("2026-10-04T00:00:00Z"); UUID older = incident(data.first(), at), newer = incident(data.first(), at.plusSeconds(1));
        var first = json.readTree(mvc.perform(request().queryParam("limit", "1")).andReturn().getResponse().getContentAsString());
        assertThat(first.path("items").get(0).path("id").asString()).isEqualTo(newer.toString());
        String cursor = first.path("nextCursor").asString(); assertThat(first.path("hasMore").asBoolean()).isTrue();
        var second = json.readTree(mvc.perform(request().queryParam("limit", "1").queryParam("cursor", cursor)).andReturn().getResponse().getContentAsString());
        assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(older.toString());
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        status(request().queryParam("limit", "2").queryParam("cursor", cursor), 400);
        status(request().queryParam("limit", "1").queryParam("cursor", cursor + "x"), 400);
        status(get(path(data.runtime().projectId(), data.second())).queryParam("expectedModelVersionId", data.model().toString())
                .queryParam("limit", "1").queryParam("cursor", cursor).header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 400);
        var other = WebAppDataRuntimeFixture.seed(owner);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')", UUID.randomUUID(), data.runtime().projectId(), other.runtime().actorId());
        token = tokens.issue(new AuthenticatedPrincipal(other.runtime().actorId(), other.runtime().tenantId(), data.runtime().projectId())).value();
        status(request().queryParam("limit", "1").queryParam("cursor", cursor), 400);
    }
    @Test void rejectsUnknownDuplicateEmptyAndUnsupportedTimeWindowParameters() throws Exception {
        for (String name : List.of("extra", "from", "to", "severity")) status(request().queryParam(name, "x"), 400);
        for (String name : List.of("expectedModelVersionId", "limit", "cursor")) status(request().queryParam(name, "1", "1"), 400);
        for (String value : List.of("0", "51", "")) status(request().queryParam("limit", value), 400);
        status(request().queryParam("cursor", ""), 400);
        status(request().queryParam("cursor", "x".repeat(4097)), 400);
    }
    @Test void emptyPageStaleIdentityAndForeignScopeAreExplicit() throws Exception {
        var body = json.readTree(mvc.perform(request()).andReturn().getResponse().getContentAsString());
        assertThat(body.path("items").isEmpty()).isTrue(); assertThat(body.toString()).doesNotContain("NORMAL");
        var foreign = WebAppDataRuntimeFixture.seed(owner);
        status(get(path(foreign.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
        status(get(path(data.runtime().projectId(), foreign.first())).queryParam("expectedModelVersionId", foreign.model().toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
        token = tokens.issue(new AuthenticatedPrincipal(data.runtime().actorId(), data.runtime().tenantId(), data.runtime().projectId(), 999)).value();
        status(request(), 401);
    }
    @Test void revocationAfterSourceReadRejectsAlreadyCollectedFacts() throws Exception {
        doAnswer(call -> { var value = call.callRealMethod();
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", data.runtime().projectId(), data.runtime().actorId());
            return value;
        }).when(alarms).query(any(), anyList(), anyList(), anyList(), anyList(), any(), any());
        status(request(), 404);
    }
    @Test void sourceFailureCannotBecomeEmptySuccess() throws Exception {
        doThrow(new IllegalStateException("fixed source failure")).when(alarms).query(any(), anyList(), anyList(), anyList(), anyList(), any(), any());
        status(request(), 500);
    }
    @Test void modelChangedAfterSourceReadRejectsCollectedPage() throws Exception {
        UUID next = UUID.randomUUID();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            SELECT ?,tenant_id,project_id,device_type_id,'1.1.0',1,1,0,'MINOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
              FROM dev_thing_model_version WHERE id=?
            """, next, data.model());
        doAnswer(call -> { var value = call.callRealMethod();
            owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?", next, data.first());
            return value;
        }).when(alarms).query(any(), anyList(), anyList(), anyList(), anyList(), any(), any());
        status(request(), 409);
    }
}
