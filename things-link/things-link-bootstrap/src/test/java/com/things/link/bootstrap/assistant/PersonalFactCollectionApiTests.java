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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
class PersonalFactCollectionApiTests extends AbstractAssistantIntegrationTest {
    @Autowired MockMvc mvc;@Autowired TokenIssuer tokens;
    @MockitoSpyBean PersonalEvidenceRecordRepository records;
    @MockitoSpyBean DeviceEvidenceService evidence;
    JdbcTemplate owner;DataFixture data;String token,a,b;final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void seed() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),f.projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        a=save(data.first());b=save(data.second());clearInvocations(evidence);
    }
    String prefix(){return "/api/v1/projects/"+data.runtime().projectId()+"/assistant";}
    String path(){return prefix()+"/fact-reports/collection";}
    String save(UUID device) throws Exception {
        var body="{\"deviceId\":\""+device+"\",\"expectedModelVersionId\":\""+data.model()+"\",\"propertyKeys\":[\"temperature\",\"secret\"]}";
        var r=mvc.perform(post(prefix()+"/evidence-records").header(HttpHeaders.AUTHORIZATION,"Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);return json.readTree(r.getContentAsString()).path("id").asString();
    }
    String input(String... ids){return json.writeValueAsString(Map.of("recordIds",List.of(ids)));}
    org.springframework.mock.web.MockHttpServletResponse report(String body,int status) throws Exception {
        var r=mvc.perform(post(path()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).header("Idempotency-Key","same-collection-key").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");return r;
    }
    @Test void fourCurrentRolesGetOwnCanonicalSelectedCoverageWithoutWritesOrNewEvidence() throws Exception {
        String original=null;
        for(String role:List.of("OWNER","ADMIN","OPERATOR","VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,data.runtime().projectId(),data.runtime().actorId());
            var r=report(input(b,a),200);assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            var body=json.readTree(r.getContentAsString());assertThat(body.path("coverage").path("devices").asInt()).isEqualTo(2);
            assertThat(body.path("coverage").path("selectedProperties").asInt()).isEqualTo(4);
            assertThat(body.path("coverage").path("unavailableValues").asInt()).isEqualTo(4);
            assertThat(body.path("sourceRecords").size()).isEqualTo(2);assertThat(body.path("scope").asString()).isEqualTo("SELECTED_PERSONAL_RECORDS");
            assertThat(body.path("contentSha256").asString()).isEqualTo(PersonalEvidenceRecordService.sha256(body.path("markdown").asString()));
            if(original==null)original=r.getContentAsString();else assertThat(r.getContentAsString()).isEqualTo(original);
        }
        assertThat(report(input(a,b),200).getContentAsString()).isEqualTo(original);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(2);
        for(String table:List.of("assistant_analysis_call","assistant_probe_batch","assistant_model_configuration"))
            assertThat(owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
        verifyNoInteractions(evidence);
    }
    @Test void anotherCreatorIncludingCrossTenantAdminAndWrongCurrentProjectAreRejected() throws Exception {
        var other=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),other.runtime().actorId());
        token=tokens.issue(new AuthenticatedPrincipal(other.runtime().actorId(),other.runtime().tenantId(),f.projectId())).value();report(input(a,b),404);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",UUID.randomUUID(),other.runtime().projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),other.runtime().projectId())).value();report(input(a,b),404);
    }
    @Test void expiredMissingOrDuplicateDeviceSourceCannotBecomePartialSuccess() throws Exception {
        String same=save(data.first());clearInvocations(evidence);report(input(a,same),400);
        report(input(a,UUID.randomUUID().toString()),404);
        owner.update("UPDATE assistant_evidence_record SET created_at=now()-interval '31 days',expires_at=now()-interval '1 day' WHERE id=?",UUID.fromString(b));
        var response=report(input(a,b),404);assertThat(response.getContentAsString()).doesNotContain("markdown","个人设备集合历史事实报告",a);
    }
    @Test void deletionBeforeUnifiedFinalQueryRejectsAnEarlierSuccessfulSource() throws Exception {
        doAnswer(call->{owner.update("DELETE FROM assistant_evidence_record WHERE id=?",UUID.fromString(a));return call.callRealMethod();})
                .when(records).list(any(),any(),any());
        var r=report(input(a,b),404);assertThat(r.getContentAsString()).doesNotContain("markdown",a,b);
    }
    @Test void currentRoleRevocationAfterUnifiedSourceQueryStillRejectsReturn() throws Exception {
        doAnswer(call->{var result=call.callRealMethod();owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());return result;})
                .when(records).list(any(),any(),any());
        report(input(a,b),404);
    }
    @Test void closedBodySizeCanonicalIdsQueriesAndAnonymousAreEnforced() throws Exception {
        for(String body:List.of("{}","null","{\"recordIds\":null}",input(),input(a,a),input(a,b,a,b,a,b),
                "{\"recordIds\":[\""+a+"\"],\"recordIds\":[\""+b+"\"]}",input(a)+"{}",
                "{\"recordIds\":[true]}","{\"recordIds\":[null]}","{\"recordIds\":[\"bad\"]}",
                "{\"recordIds\":[\""+a+"\"],\"projectId\":\"raw\"}"," ".repeat(2049)+input(a))) report(body,400);
        assertThat(mvc.perform(post(path()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(input(a)).queryParam("template","custom")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(input(a))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void archiveAllowsHistoricalViewButCorruptHashNeverProducesBody() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",data.runtime().projectId());report(input(a,b),200);
        owner.update("UPDATE assistant_evidence_record SET content_sha256=? WHERE id=?","b".repeat(64),UUID.fromString(a));
        assertThat(report(input(a,b),500).getContentAsString()).doesNotContain("markdown","统计分母");
    }
}
