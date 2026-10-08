package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.DeviceEvidenceService;
import com.things.link.assistant.application.PersonalEvidenceRecordService;
import com.things.link.assistant.domain.PersonalEvidenceRecordRepository;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
class PersonalFactReportApiTests extends AbstractAssistantIntegrationTest {
    @Autowired MockMvc mvc;@Autowired TokenIssuer tokens;
    @MockitoSpyBean PersonalEvidenceRecordRepository records;
    @MockitoSpyBean DeviceEvidenceService evidence;
    JdbcTemplate owner;DataFixture data;String token,id;final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void seed() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),f.projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        String body="{\"deviceId\":\""+data.first()+"\",\"expectedModelVersionId\":\""+data.model()+"\",\"propertyKeys\":[\"temperature\",\"secret\"]}";
        var response=mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        id=json.readTree(response.getContentAsString()).path("id").asString();clearInvocations(evidence);
    }
    String base() { return "/api/v1/projects/"+data.runtime().projectId()+"/assistant/evidence-records"; }
    String path() { return base()+"/"+id+"/fact-report"; }
    org.springframework.mock.web.MockHttpServletResponse report(int status) throws Exception {
        var r=mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token)).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);return r;
    }
    @Test void allFourRolesGenerateDeterministicOwnHistoricalReportWithoutNewEvidenceOrWrites() throws Exception {
        String original=null;
        for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,data.runtime().projectId(),data.runtime().actorId());
            var r=report(200);assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            var result=json.readTree(r.getContentAsString());assertThat(result.path("mode").asString()).isEqualTo("FACTS_ONLY");
            assertThat(result.path("sourceRecord").path("id").asString()).isEqualTo(id);
            String markdown=result.path("markdown").asString();
            assertThat(result.path("contentSha256").asString()).isEqualTo(PersonalEvidenceRecordService.sha256(markdown));
            assertThat(markdown).contains("未取得可用值 2 个","单位未提供","不代表设备当前状态");
            if(original==null)original=r.getContentAsString();else assertThat(r.getContentAsString()).isEqualTo(original);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(1);
        for(String table:List.of("assistant_analysis_call","assistant_probe_batch","assistant_model_configuration"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
        verifyNoInteractions(evidence);
    }
    @Test void projectAdminCannotReadOtherCreatorAndWrongCurrentProjectRejected() throws Exception {
        var other=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),other.runtime().actorId());
        token=tokens.issue(new AuthenticatedPrincipal(other.runtime().actorId(),other.runtime().tenantId(),f.projectId())).value();report(404);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),other.runtime().projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),other.runtime().projectId())).value();report(404);
    }
    @Test void deletionExpiryAndRevocationInvalidateReportSource() throws Exception {
        owner.update("UPDATE assistant_evidence_record SET created_at=now()-interval '31 days',expires_at=now()-interval '1 day' WHERE id=?",UUID.fromString(id));report(404);
        owner.update("UPDATE assistant_evidence_record SET created_at=now(),expires_at=now()+interval '30 days' WHERE id=?",UUID.fromString(id));
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());report(401);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),data.runtime().projectId(),data.runtime().actorId());
        owner.update("DELETE FROM assistant_evidence_record WHERE id=?",UUID.fromString(id));report(404);
    }
    @Test void finalFreshReadRejectsRevocationDuringRendering() throws Exception {
        var reads=new AtomicInteger();
        doAnswer(call->{var result=call.callRealMethod();if(reads.incrementAndGet()==2)
                owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());return result;})
                .when(records).find(any(),any(),any(),any());
        report(404);assertThat(reads).hasValue(2);
    }
    @Test void archivedSourceReadableButUncontrolledQueriesAndAnonymousRequestsRejected() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",data.runtime().projectId());report(200);
        var query=mvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).queryParam("template","custom")).andReturn().getResponse();
        assertThat(query.getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get(path())).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void invalidStoredHashDoesNotBecomeAReport() throws Exception {
        owner.update("UPDATE assistant_evidence_record SET content_sha256=? WHERE id=?","b".repeat(64),UUID.fromString(id));
        var r=report(500);assertThat(r.getContentAsString()).doesNotContain("历史事实报告","markdown");
    }
}
