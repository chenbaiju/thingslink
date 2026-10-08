package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.iam.application.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AnalysisRunApiTests extends AbstractAssistantIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("things-link.assistant.credentials.active-key-id",()->"analysis-api");r.add("things-link.assistant.credentials.keys.analysis-api",()->MASTER);
    }
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired ModelConfigurationService models;
    @Autowired AnalysisCallService calls;
    @MockitoBean AnalysisTransport transport;
    JdbcTemplate owner;WebAppDataRuntimeFixture.DataFixture data;String token,path,body;
    AtomicReference<byte[]> delivered=new AtomicReference<>();
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup(){
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),f.actorId());
        TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try{models.replaceAndEnable(f.projectId(),new ModelCredentialInput("0","synthetic-analysis-api-only"));}finally{TenantContext.clear();}
        when(transport.ready()).thenReturn(true);
        when(transport.execute(any(),any(),any())).thenAnswer(i->{delivered.set(i.getArgument(2));return AnalysisTransport.Receipt.UNQUALIFIED;});
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        path="/api/v1/projects/"+f.projectId()+"/assistant/analysis-runs";
        body=json.writeValueAsString(new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY));
    }
    String key(){return Uuid7.generate().toString();}
    MockHttpServletRequestBuilder submit(String key,String body){return post(path).contentType("application/json").header("Idempotency-Key",key).content(body);}
    MockHttpServletResponse call(MockHttpServletRequestBuilder request,int status)throws Exception {
        var response=mvc.perform(request.header("Authorization","Bearer "+token)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).doesNotContain("synthetic-analysis-api-only",MASTER,"ciphertext","private-transport-error");
        if(status!=401)assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return response;
    }
    int records(){return owner.queryForObject("SELECT count(*) FROM assistant_analysis_call WHERE project_id=?",Integer.class,data.runtime().projectId());}
    MockHttpServletRequestBuilder lookup(String key){return get(path+"/by-key").header("Idempotency-Key",key);}
    @Test void syntheticTrustedResultIsTransientAndReplayNeverReturnsBody(CapturedOutput output)throws Exception {
        when(transport.execute(any(),any(),any())).thenAnswer(i -> {
            delivered.set(i.getArgument(2));
            com.things.link.assistant.domain.AnalysisCall original=i.getArgument(0);
            return new AnalysisTransport.Receipt.Releasable(AnalysisResultPermitFixture.issue(original,original.deadline()));
        });
        String key=key();var first=json.readTree(call(submit(key,body),200).getContentAsString());
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("call","category","result");
        assertThat(first.path("category").asString()).isEqualTo("SUCCEEDED");
        assertThat(first.path("call").path("status").asString()).isEqualTo("SUCCEEDED");
        var result=first.path("result");
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("model","promptVersion","summary","findings","limitations","usage");
        assertThat(result.path("model").asString()).isEqualTo("deepseek-flash");
        assertThat(result.path("promptVersion").asString()).isEqualTo("thingslink-agent-single-analysis-v1");
        assertThat(result.path("summary").asString()).isEqualTo("synthetic-private-summary");
        assertThat(result.path("findings").get(0).path("kind").asString()).isEqualTo("HYPOTHESIS");
        assertThat(result.path("findings").get(0).path("evidenceIds").get(0).asString()).isEqualTo("e-device");
        assertThat(result.path("usage").path("totalTokens").asInt()).isEqualTo(12);
        var replay=json.readTree(call(submit(key,body),200).getContentAsString());
        assertThat(replay.path("category").asString()).isEqualTo("REPLAY");assertThat(replay.path("result").isNull()).isTrue();
        String id=first.path("call").path("id").asString();
        assertThat(call(get(path+"/"+id),200).getContentAsString()).doesNotContain("summary","result","synthetic-private");
        assertThat(call(lookup(key),200).getContentAsString()).doesNotContain("summary","result","synthetic-private");
        assertThat(owner.queryForObject("SELECT row_to_json(c)::text FROM assistant_analysis_call c WHERE id=?",String.class,UUID.fromString(id)))
                .doesNotContain("summary","usage","synthetic-private");
        assertThat(delivered.get()).isEqualTo(new byte[delivered.get().length]);
        verify(transport,times(1)).execute(any(),any(),any());
        assertThat(call(get(path+"/status"),200).getContentAsString()).contains("\"businessAvailable\":false");
        assertThat(output.getAll()).doesNotContain("synthetic-private-summary","synthetic-private-statement");
    }
    @Test void permissionRevokedDuringSyntheticResultPreventsHttpBody()throws Exception {
        when(transport.execute(any(),any(),any())).thenAnswer(i -> {
            delivered.set(i.getArgument(2));
            com.things.link.assistant.domain.AnalysisCall original=i.getArgument(0);
            var permit=AnalysisResultPermitFixture.issue(original,original.deadline());
            owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
            return new AnalysisTransport.Receipt.Releasable(permit);
        });
        assertThat(call(submit(key(),body),403).getContentAsString()).doesNotContain("synthetic-private","summary","usage");
        assertThat(delivered.get()).isEqualTo(new byte[delivered.get().length]);
    }
    @Test void keyLookupFindsIndependentCallIdWithoutPreparingOrExecuting()throws Exception {
        String key=key();var f=data.runtime();AnalysisCallView call;
        TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try{call=calls.prepare(f.projectId(),key,new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY)).call();
            models.enable(f.projectId(),models.read(f.projectId()).revision(),false);
        }finally{TenantContext.clear();}
        assertThat(call.id().toString()).isNotEqualTo(key);
        when(transport.ready()).thenReturn(false);
        for(String role:List.of("OWNER","ADMIN","OPERATOR")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,f.projectId(),f.actorId());
            var view=json.readTree(call(lookup(key),200).getContentAsString());
            assertThat(view.path("id").asString()).isEqualTo(call.id().toString());
            assertThat(view.path("status").asString()).isEqualTo("RESERVED");
            assertThat(view.propertyNames()).containsExactlyInAnyOrder("id","status","createdAt","deadline","expiresAt","dispatchedAt","finishedAt");
        }
        assertThat(records()).isEqualTo(1);verify(transport,never()).execute(any(),any(),any());
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,f.projectId())).isZero();
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",f.projectId(),f.actorId());
        call(lookup(key),403);
        owner.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",f.projectId(),f.actorId());
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",data.first());
        call(lookup(key),404);
    }
    @Test void unknownOrInvalidLookupNeverCreatesAnExecution()throws Exception {
        call(lookup(key()),404);
        call(get(path+"/by-key"),400);call(lookup(key()).header("Idempotency-Key",key()),400);
        for(String invalid:List.of("",UUID.randomUUID().toString(),key().toUpperCase(Locale.ROOT),"not-a-key"))call(lookup(invalid),400);
        long old=java.time.Instant.now().minusSeconds(86410).toEpochMilli();
        call(lookup(new UUID((old<<16)|0x7000,0x8000000000000000L).toString()),400);
        call(lookup(key()).queryParam("callId","forbidden"),400);
        assertThat(records()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void fixedRunOnlyReturnsMetadataAndSameKeyRevalidatesRatherThanServingCache(CapturedOutput output)throws Exception {
        String key=key();var first=json.readTree(call(submit(key,body),200).getContentAsString());
        assertThat(first.path("category").asString()).isEqualTo("UNQUALIFIED");
        assertThat(first.path("call").path("status").asString()).isEqualTo("UNKNOWN");
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("call","category","result");
        assertThat(first.path("result").isNull()).isTrue();
        assertThat(call(submit(key,body),200).getContentAsString()).contains("REPLAY");
        String id=first.path("call").path("id").asString();call(get(path+"/"+id),200);
        call(submit(key,body.replace("STATUS_SUMMARY","ALARM_EXPLANATION")),409);
        verify(transport,times(1)).execute(any(),any(),any());assertThat(records()).isEqualTo(1);
        assertThat(delivered.get()).isEqualTo(new byte[delivered.get().length]);
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
        call(submit(key,body),403);call(get(path+"/"+id),403);call(get(path+"/status"),403);
        assertThat(output.getAll()).doesNotContain("synthetic-analysis-api-only",MASTER);
    }
    @Test void disabledAndConfiguredStatusNeverAdvertiseBusinessAdmission()throws Exception {
        assertThat(call(get(path+"/status"),200).getContentAsString()).contains("\"businessAvailable\":false","MODEL_ADMISSION_PENDING");
        when(transport.ready()).thenReturn(false);
        assertThat(call(get(path+"/status"),200).getContentAsString()).contains("INTERNAL_TRANSPORT_DISABLED");
        var view=json.readTree(call(submit(key(),body),200).getContentAsString());
        assertThat(view.path("category").asString()).isEqualTo("UNAVAILABLE");assertThat(view.path("call").isNull()).isTrue();
        assertThat(view.path("result").isNull()).isTrue();
        call(submit(UUID.randomUUID().toString(),body),400);
        long old=java.time.Instant.now().minusSeconds(86410).toEpochMilli();
        call(submit(new UUID((old<<16)|0x7000,0x8000000000000000L).toString(),body),400);
        assertThat(records()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void closedBodyHeadersAndQueryNeverCreateOrSend()throws Exception {
        for(String bad:List.of(body.replace("\"template\":", "\"unknown\":1,\"template\":"),body+" {}",
                body.replace("\"template\":", "\"template\":\"STATUS_SUMMARY\",\"template\":"),
                body.replace("[\"temperature\"]","[]"),body.replace("[\"temperature\"]","[12]"),
                body.replace("STATUS_SUMMARY","free prompt")," ".repeat(4097),"null")) call(submit(key(),bad),400);
        call(post(path).contentType("application/json").content(body),400);
        call(submit(key(),body).header("Idempotency-Key",key()),400);
        call(submit(key(),body).queryParam("apiKey","forbidden"),400);
        call(get(path+"/status").queryParam("projectId","forbidden"),400);
        assertThat(records()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void unknownTransportHasDurableReplayProtectionWithoutBodyOrZeroUsage(CapturedOutput output)throws Exception {
        doThrow(new IllegalStateException("private-transport-error")).when(transport).execute(any(),any(),any());
        String key=key();assertThat(call(submit(key,body),200).getContentAsString()).contains("UNKNOWN","TRANSPORT_UNKNOWN");
        assertThat(call(submit(key,body),200).getContentAsString()).contains("REPLAY");
        verify(transport,times(1)).execute(any(),any(),any());
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("private-transport-error","synthetic-analysis-api-only");
    }
    @Test void anotherProjectAndAnotherCreatorCannotReadOrReplay()throws Exception {
        String key=key();var view=json.readTree(call(submit(key,body),200).getContentAsString());
        String id=view.path("call").path("id").asString();String original=path;
        path=path.replace(data.runtime().projectId().toString(),UUID.randomUUID().toString());
        call(get(path+"/status"),404);call(lookup(key),404);call(submit(key(),body),404);path=original;
        UUID actor=UUID.randomUUID();var f=data.runtime();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES(?,?,'synthetic','other')",actor,actor+"@example.test");
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OPERATOR')",UUID.randomUUID(),f.projectId(),actor);
        token=tokens.issue(new AuthenticatedPrincipal(actor,f.tenantId(),f.projectId())).value();
        call(get(path+"/"+id),404);call(lookup(key),404);assertThat(call(submit(key,body),200).getContentAsString()).contains("UNQUALIFIED");
        assertThat(json.readTree(call(lookup(key),200).getContentAsString()).path("id").asString()).isNotEqualTo(id);
        assertThat(records()).isEqualTo(2);
    }

    @Test void reviewedStatusRequiresPaidRoleAndEnabledProjectWithoutExecuting()throws Exception {
        when(transport.review()).thenReturn(Optional.of(AnalysisResultPermitFixture.review(java.time.Instant.now())));
        var f=data.runtime();
        for(String role:List.of("OWNER","ADMIN","OPERATOR")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,f.projectId(),f.actorId());
            var result=json.readTree(call(get(path+"/status"),200).getContentAsString());
            assertThat(result.propertyNames()).containsExactlyInAnyOrder("businessAvailable","reason");
            assertThat(result.path("businessAvailable").asBoolean()).isTrue();
            assertThat(result.path("reason").asString()).isEqualTo("REVIEWED_CONFIGURATION_AVAILABLE");
        }
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",f.projectId(),f.actorId());
        call(get(path+"/status"),403);
        owner.update("UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?",f.projectId(),f.actorId());
        TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try { models.enable(f.projectId(),"2",false); } finally { TenantContext.clear(); }
        assertThat(call(get(path+"/status"),200).getContentAsString())
            .contains("\"businessAvailable\":false","PROJECT_MODEL_CONFIGURATION_DISABLED");
        assertThat(records()).isZero();assertThat(delivered.get()).isNull();
        verify(transport,never()).execute(any(),any(),any());
    }
    @Test void expiredOrRevokedReviewCannotAdvertiseAvailability()throws Exception {
        when(transport.review()).thenReturn(Optional.of(AnalysisResultPermitFixture.review(java.time.Instant.now().minusSeconds(7200))));
        assertThat(call(get(path+"/status"),200).getContentAsString()).contains("\"businessAvailable\":false","MODEL_ADMISSION_PENDING");
        when(transport.review()).thenReturn(Optional.of(AnalysisResultPermitFixture.review(java.time.Instant.now())));
        call(get(path+"/status"),200);
        when(transport.review()).thenReturn(Optional.empty());
        assertThat(call(get(path+"/status"),200).getContentAsString()).contains("\"businessAvailable\":false","MODEL_ADMISSION_PENDING");
        assertThat(records()).isZero();assertThat(delivered.get()).isNull();verify(transport,never()).execute(any(),any(),any());
    }
}
