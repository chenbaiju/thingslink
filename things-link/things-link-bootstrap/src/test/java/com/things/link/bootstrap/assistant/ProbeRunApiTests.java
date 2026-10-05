package com.things.link.bootstrap.assistant;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.iam.application.*;
import com.things.link.shared.tenant.*;
import com.things.link.testing.AbstractIntegrationTest;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProbeRunApiTests extends AbstractIntegrationTest {
    static final String MASTER=ModelConfigurationApiTests.master();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("things-link.assistant.credentials.active-key-id",()->"probe-api");r.add("things-link.assistant.credentials.keys.probe-api",()->MASTER);
    }
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired ModelConfigurationService models;
    @Autowired AnalysisCallService calls;
    @Autowired ProbeLedgerService ledger;
    @Autowired ProbeExecutionService execution;
    @Autowired JdbcTemplate app;
    @MockitoBean ProbeAuthorizationProvider grants;
    @MockitoBean ProbeTransport transport;
    JdbcTemplate owner;WebAppDataRuntimeFixture.DataFixture data;ProbeAuthorization grant;String token,path;
    String secret="synthetic-probe-api-only";AtomicReference<byte[]> delivered=new AtomicReference<>();
    @BeforeEach void setup(){
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),f.actorId());
        TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));try{models.replaceAndEnable(f.projectId(),new ModelCredentialInput("0",secret));}finally{TenantContext.clear();}
        grant=new ProbeAuthorization("api-"+f.projectId(),"test",f.projectId(),2,"a".repeat(64));
        when(grants.current()).thenReturn(Optional.of(grant));when(grants.find(grant.id())).thenReturn(Optional.of(grant));
        when(transport.ready()).thenReturn(true);
        when(transport.execute(any(),any(),any())).thenAnswer(i->{Attempt a=i.getArgument(0);byte[] key=i.getArgument(2);
            assertThat(new String(key,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo(secret);delivered.set(key);
            return new ProbeResult(a.sampleIndex(),grant.manifestSha256(),"SUCCEEDED","COUNT_MISMATCH",
                new ProbeResult.Usage(477,478,1,479,0,478,1,"length","a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64)));});
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();path="/api/v1/projects/"+f.projectId()+"/assistant/model-probes/";
    }
    String call(int index,int expected)throws Exception{
        var r=mvc.perform(post(path+index).header("Authorization","Bearer "+token)).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(expected);assertThat(r.getContentAsString()).doesNotContain(secret,MASTER,"ciphertext");
        if(expected==200)assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");return r.getContentAsString();
    }
    int attempts(){return owner.queryForObject("SELECT count(*) FROM assistant_probe_attempt WHERE project_id=?",Integer.class,data.runtime().projectId());}
    @Test void normalHttpSendsOnceWipesKeyAndDoesNotQualifyBusiness(CapturedOutput output)throws Exception{
        assertThat(call(1,200)).contains("SUCCEEDED","COUNT_MISMATCH","\"delta\":1");call(1,409);call(0,400);call(4,400);
        assertThat(mvc.perform(post(path+1).header("Authorization","Bearer "+token).header("Idempotency-Key","synthetic-replay"))
            .andReturn().getResponse().getStatus()).isEqualTo(409);
        verify(transport,times(1)).execute(any(),any(),any());assertThat(delivered.get()).containsOnly((byte)0);assertThat(attempts()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,data.runtime().projectId())).isZero();
        assertThat(output.getAll()).doesNotContain(secret,MASTER);
    }
    @Test void noAuthorizationOrTransportDoesNotConsumeAnyChance()throws Exception{
        when(grants.current()).thenReturn(Optional.empty());call(1,409);when(grants.current()).thenReturn(Optional.of(grant));when(transport.ready()).thenReturn(false);call(1,409);
        assertThat(attempts()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void viewerRevokedWrongProjectAndClosedInputNeverSend()throws Exception{
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());call(1,403);
        owner.update("UPDATE sys_project_member SET role='OPERATOR' WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
        for(var b:List.of(post(path+1).content("{}"),post(path+1).queryParam("apiKey",secret),post(path+"01"),post(path+"+1"),post(path+"1.0")))assertThat(mvc.perform(b.header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(400);
        String original=path;path=path.replace(data.runtime().projectId().toString(),UUID.randomUUID().toString());call(1,404);path=original;
        assertThat(attempts()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void unknownTransportConsumesChanceAndRetainsSlotUntilDeadline(CapturedOutput output)throws Exception{
        doThrow(new IllegalStateException("synthetic-transport-secret")).when(transport).execute(any(),any(),any());
        assertThat(call(1,200)).contains("UNKNOWN","TRANSPORT_UNKNOWN");call(1,409);
        assertThat(attempts()).isEqualTo(1);assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(secret,"synthetic-transport-secret");
    }
    @Test void stoppedOrChangedKeyRevisionCannotSend()throws Exception{
        var f=data.runtime();TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));try{models.enable(f.projectId(),"2",false);}finally{TenantContext.clear();}
        call(1,409);assertThat(attempts()).isZero();verify(transport,never()).execute(any(),any(),any());
    }
    @Test void dispatchedFactCannotResetAfterSlotRelease()throws Exception{
        var f=data.runtime();TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try{
            var a=ledger.claim(f.projectId(),grant.id(),1);var p=execution.dispatch(f.projectId(),a,grant);
            assertThat(execution.release(f.projectId(),p)).isTrue();
            assertThatThrownBy(()->execution.dispatch(f.projectId(),a,grant)).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        }finally{TenantContext.clear();}
        verify(transport,never()).execute(any(),any(),any());
    }
    @Test void businessAndProbeShareSingleUserAdmission()throws Exception{
        var f=data.runtime();TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try{
            var request=new AnalysisRequest(data.first(),data.model(),List.of("temperature"),PreparedModelEvidence.Template.STATUS_SUMMARY);
            var reserved=calls.prepare(f.projectId(),com.things.link.shared.id.Uuid7.generate().toString(),request).call();
            var busy=calls.dispatch(f.projectId(),reserved.id(),request);var a=ledger.claim(f.projectId(),grant.id(),1);
            assertThatThrownBy(()->execution.dispatch(f.projectId(),a,grant)).isInstanceOf(com.things.link.shared.error.BusinessException.class)
                .satisfies(e->assertThat(((com.things.link.shared.error.BusinessException)e).errorCode().httpStatus()).isEqualTo(429));
            assertThat(calls.releasePermit(f.projectId(),busy)).isTrue();var p=execution.dispatch(f.projectId(),a,grant);
            var next=calls.prepare(f.projectId(),com.things.link.shared.id.Uuid7.generate().toString(),request).call();
            assertThatThrownBy(()->calls.dispatch(f.projectId(),next.id(),request)).isInstanceOf(com.things.link.shared.error.BusinessException.class);
            execution.release(f.projectId(),p);
        }finally{TenantContext.clear();}
    }
    @Test void mixedUsersStillHaveOnlyTwoProjectSlots()throws Exception {
        doThrow(new IllegalStateException("fixed-unknown")).when(transport).execute(any(),any(),any());
        call(1,200);
        for(int index=2;index<=3;index++){
            UUID actor=UUID.randomUUID();var f=data.runtime();
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'synthetic','probe-member')",actor,actor+"@example.test");
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OPERATOR')",UUID.randomUUID(),f.projectId(),actor);
            token=tokens.issue(new AuthenticatedPrincipal(actor,f.tenantId(),f.projectId())).value();call(index,200);
        }
        verify(transport,times(2)).execute(any(),any(),any());assertThat(attempts()).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM assistant_analysis_slot WHERE project_id=?",Integer.class,data.runtime().projectId())).isEqualTo(2);
    }
}
