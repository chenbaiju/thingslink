package com.things.link.bootstrap.assistant;
import com.things.link.assistant.domain.PersonalEvidenceRecordRepository;
import com.things.link.assistant.application.DeviceEvidenceService;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@AutoConfigureMockMvc
class PersonalEvidenceRecordApiTests extends AbstractAssistantIntegrationTest {
    @Autowired MockMvc mvc; @Autowired TokenIssuer tokens; @Autowired JdbcTemplate application;
    @MockitoSpyBean PersonalEvidenceRecordRepository records;
    @MockitoSpyBean DeviceEvidenceService evidence;
    JdbcTemplate owner;DataFixture data;String token;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),f.projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
    }
    String path() {return "/api/v1/projects/"+data.runtime().projectId()+"/assistant/evidence-records";}
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r) {return r.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);}
    String body() {return "{\"deviceId\":\""+data.first()+"\",\"expectedModelVersionId\":\""+data.model()+"\",\"propertyKeys\":[\"temperature\",\"secret\"]}";}
    String create() throws Exception {
        var r=mvc.perform(auth(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        var node=json.readTree(r.getResponse().getContentAsString());
        assertThat(node.has("content")).isFalse();assertThat(node.has("createdBy")).isFalse();
        assertThat(r.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(node.path("id").asString());
        return node.path("id").asString();
    }
    void status(MockHttpServletRequestBuilder r,int expected) throws Exception {
        var result=mvc.perform(r).andReturn();assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
    }
    @Test void allRolesSaveOwnRecordsAndOnlySafeServerFactsAreStored() throws Exception {
        var f=data.runtime();
        owner.update("""
            INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version,reported_sequence,reported_revisions)
            VALUES (?,?,?,'{"temperature":12.5,"secret":"sensitive"}','{"temperature":"2026-10-04T00:00:00Z","secret":"2026-10-04T00:00:00Z"}',?::jsonb,1,'{"temperature":"1","secret":"1"}')
            """,data.first(),f.tenantId(),f.projectId(),"{\"temperature\":\""+data.model()+"\",\"secret\":\""+data.model()+"\"}");
        for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,f.projectId(),f.actorId());
            String id=create();var result=mvc.perform(auth(get(path()+"/"+id))).andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            String content=result.getResponse().getContentAsString();assertThat(content).doesNotContain("sensitive","apiKey","tenantId","createdBy");
            var detail=json.readTree(content);assertThat(detail.path("snapshot").path("properties").get(0).path("value").asDouble()).isEqualTo(12.5);
            assertThat(detail.path("snapshot").path("properties").get(1).path("valueOmitted").asBoolean()).isTrue();
        }
        var r=mvc.perform(auth(get(path()))).andReturn();assertThat(json.readTree(r.getResponse().getContentAsString()).size()).isEqualTo(4);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("snapshot","properties","sensitive");
    }
    @Test void otherProjectMemberIncludingAdminCannotReadOrDeleteCreatorRecords() throws Exception {
        String id=create();var foreign=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),foreign.runtime().actorId());
        token=tokens.issue(new AuthenticatedPrincipal(foreign.runtime().actorId(),foreign.runtime().tenantId(),f.projectId())).value();
        status(auth(get(path()+"/"+id)),404);status(auth(delete(path()+"/"+id)),404);
        var empty=mvc.perform(auth(get(path()))).andReturn();assertThat(json.readTree(empty.getResponse().getContentAsString()).size()).isZero();
        String own=create();status(auth(get(path()+"/"+own)),200);
        assertThat(owner.queryForObject("SELECT tenant_id FROM assistant_evidence_record WHERE id=?",UUID.class,UUID.fromString(own))).isEqualTo(f.tenantId());
    }
    @Test void closedSelectorsAndQueryCannotSubmitFactsOrIdentity() throws Exception {
        String[] invalid={"{}",body()+"{}","{\"deviceId\":\""+data.first()+"\","+body().substring(1)," ".repeat(4097),body().replace("temperature","unknown"),body().replace("]}",",\"temperature\"]}"),body().replace("}",",\"content\":\"forged\"}")};
        for(String b:invalid) status(auth(post(path()).contentType(MediaType.APPLICATION_JSON).content(b)),400);
        status(auth(post(path()).queryParam("extra","x").contentType(MediaType.APPLICATION_JSON).content(body())),400);
        status(auth(get(path()).queryParam("createdBy",data.runtime().actorId().toString())),400);
    }
    @Test void revocationAfterStoredReadIsSeenBeforeResponse() throws Exception {
        String id=create();
        doAnswer(call->{var result=call.callRealMethod();owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());return result;})
                .when(records).find(any(),any(),any(),any());
        status(auth(get(path()+"/"+id)),404);
    }
    @Test void expiredRecordsAreHiddenThenReclaimedByNextOwnSave() throws Exception {
        String id=create();
        owner.update("UPDATE assistant_evidence_record SET created_at=now()-interval '31 days',expires_at=now()-interval '1 day' WHERE id=?",UUID.fromString(id));
        status(auth(get(path()+"/"+id)),404);
        var r=mvc.perform(auth(get(path()))).andReturn();assertThat(json.readTree(r.getResponse().getContentAsString()).size()).isZero();
        create();assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE id=?",Integer.class,UUID.fromString(id))).isZero();
    }
    @Test void capacityIsBoundedAndOwnDeleteReleasesSpaceWithoutTouchingOtherTables() throws Exception {
        String id=create();
        owner.update("""
            INSERT INTO assistant_evidence_record(id,tenant_id,project_id,created_by,device_id,model_version_id,content_sha256,content)
            SELECT gen_random_uuid(),tenant_id,project_id,created_by,device_id,model_version_id,content_sha256,content
            FROM assistant_evidence_record CROSS JOIN generate_series(1,99) WHERE id=?
            """,UUID.fromString(id));
        status(auth(post(path()).contentType(MediaType.APPLICATION_JSON).content(body())),429);
        status(auth(delete(path()+"/"+id)),204);status(auth(get(path()+"/"+id)),404);create();
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(100);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_call WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
    }
    @Test void sourceFailureDoesNotPersistAnEmptyRecord() throws Exception {
        doThrow(new IllegalStateException("fixed source failure")).when(evidence).read(any(),any(),any(),anyList());
        status(auth(post(path()).contentType(MediaType.APPLICATION_JSON).content(body())),500);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
    }
    @Test void publishedHistoricalFactSurvivesModelChangeButNormalAppCannotRewriteIt() throws Exception {
        String id=create();
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(data.runtime().tenantId(),data.runtime().projectId(),data.runtime().actorId()));
        try {
            assertThatThrownBy(()->application.update("UPDATE assistant_evidence_record SET content='{}' WHERE id=?",UUID.fromString(id)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { com.things.link.shared.tenant.TenantContext.clear(); }
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",data.first());
        status(auth(get(path()+"/"+id)),200);
    }
}
