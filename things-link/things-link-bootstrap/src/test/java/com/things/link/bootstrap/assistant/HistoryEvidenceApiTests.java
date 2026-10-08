package com.things.link.bootstrap.assistant;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.telemetry.application.ConsoleHistoryEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
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
class HistoryEvidenceApiTests extends AbstractAssistantIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired JdbcTemplate application;
    @MockitoSpyBean ConsoleHistoryEvidenceService history;
    JdbcTemplate owner; DataFixture data; String token; Instant from, to;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        data = WebAppDataRuntimeFixture.seed(owner);
        var f = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), f.projectId(), f.actorId());
        token = tokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        to = owner.queryForObject("SELECT statement_timestamp()", Timestamp.class).toInstant().minusSeconds(1);
        from = to.minusSeconds(3600);
    }
    String path(UUID project, UUID device) { return "/api/v1/projects/" + project + "/assistant/devices/" + device + "/history"; }
    MockHttpServletRequestBuilder request() {
        return get(path(data.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString())
                .queryParam("propertyKey", "temperature").queryParam("from", from.toString()).queryParam("to", to.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    void status(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
    }
    @Test void fourRolesHaveNoStoreVersionedHistoryAndUnknownValuesStayNull() throws Exception {
        owner.update("INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double,quality)"
                + " VALUES (?,?,'temperature',?,?,99,1)", data.runtime().projectId(), data.first(), Timestamp.from(from.plusSeconds(1)), UUID.randomUUID());
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR", "VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, data.runtime().projectId(), data.runtime().actorId());
            var result = mvc.perform(request()).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            var body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("state").asString()).isEqualTo("HAS_POINTS");
            assertThat(body.path("points").get(0).path("value").isNull()).isTrue();
            assertThat(body.path("points").get(0).path("sourceModelVersionId").isNull()).isTrue();
            assertThat(body.toString()).doesNotContain("apiKey", "secret", "tenantId", "description");
        }
    }
    @Test void unknownRepeatedAndInvalidWindowParametersReject() throws Exception {
        status(request().queryParam("extra", "x"), 400);
        for (String name : List.of("expectedModelVersionId", "propertyKey", "from", "to")) status(request().queryParam(name, "x"), 400);
        from = to.minusSeconds(86401); status(request(), 400);
        from = to; status(request(), 400);
        from = to.minusSeconds(3600); to = to.plusSeconds(86400); status(request(), 400);
    }
    @Test void staleIdentityAndOtherProjectOrDeviceCannotRead() throws Exception {
        var foreign = WebAppDataRuntimeFixture.seed(owner);
        status(get(path(foreign.runtime().projectId(), data.first())).queryParam("expectedModelVersionId", data.model().toString())
                .queryParam("propertyKey", "temperature").queryParam("from", from.toString()).queryParam("to", to.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
        status(get(path(data.runtime().projectId(), foreign.first())).queryParam("expectedModelVersionId", foreign.model().toString())
                .queryParam("propertyKey", "temperature").queryParam("from", from.toString()).queryParam("to", to.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token), 404);
        token = tokens.issue(new AuthenticatedPrincipal(data.runtime().actorId(), data.runtime().tenantId(), data.runtime().projectId(), 999)).value();
        status(request(), 401);
    }
    @Test void permissionRevokedAfterHistoryReadRejectsCollectedFacts() throws Exception {
        doAnswer(call -> {
            var value = call.callRealMethod();
            owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", data.runtime().projectId(), data.runtime().actorId());
            return value;
        }).when(history).read(any(), any(), any(), any(), any(), any());
        status(request(), 404);
    }
    @Test void modelChangedAfterHistoryReadRejectsCollectedFacts() throws Exception {
        UUID next = UUID.randomUUID();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            SELECT ?,tenant_id,project_id,device_type_id,'1.1.0',1,1,0,'MINOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
              FROM dev_thing_model_version WHERE id=?
            """, next, data.model());
        doAnswer(call -> {
            var value = call.callRealMethod();
            owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?", next, data.first()); return value;
        }).when(history).read(any(), any(), any(), any(), any(), any());
        status(request(), 409);
    }
    @Test void sourceFailureIsNotEmptyOrNormalSuccess() throws Exception {
        doThrow(new IllegalStateException("fixed test source failure")).when(history).read(any(), any(), any(), any(), any(), any());
        status(request(), 500);
    }
}
