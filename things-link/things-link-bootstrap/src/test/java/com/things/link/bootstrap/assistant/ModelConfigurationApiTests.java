package com.things.link.bootstrap.assistant;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实Console/JWT、APP角色与数据库；仅使用测试假Key，不访问供应商。 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ModelConfigurationApiTests extends AbstractIntegrationTest {
    static final String MASTER=master();
    static String master() { byte[] k=new byte[32];new SecureRandom().nextBytes(k);return Base64.getEncoder().encodeToString(k); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("things-link.assistant.credentials.active-key-id",()->"test-v1");
        r.add("things-link.assistant.credentials.keys.test-v1",()->MASTER);
    }
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired com.things.link.assistant.application.ModelConfigurationService service;
    @Autowired JdbcTemplate application;
    @MockitoSpyBean AuditLogService audit;
    @MockitoSpyBean com.things.link.assistant.domain.ModelCredentialCipher cipher;
    JdbcTemplate owner;
    DataFixture data;
    String token,path,secret;
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
        data=WebAppDataRuntimeFixture.seed(owner);var f=data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),f.projectId(),f.actorId());
        token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),f.tenantId(),f.projectId())).value();
        path="/api/v1/projects/"+f.projectId()+"/assistant/model-configurations/deepseek-chat";
        secret="synthetic-agent-secret-"+UUID.randomUUID();
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) { return b.header(HttpHeaders.AUTHORIZATION,"Bearer "+token); }
    MockHttpServletRequestBuilder replacement(String rev) { return auth(put(path)).contentType("application/json").content("{\"expectedRevision\":\""+rev+"\",\"apiKey\":\""+secret+"\"}"); }
    JsonNode call(MockHttpServletRequestBuilder b,int code) throws Exception {
        var r=mvc.perform(b).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(code);
        assertThat(r.getContentAsString()).doesNotContain(secret,MASTER,"ciphertext","nonce","encryption_key_id");
        if(code==200) { assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store"); }
        return JsonMapper.builder().build().readTree(r.getContentAsString());
    }
    void role(String value) { owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",value,data.runtime().projectId(),data.runtime().actorId()); }
    MockHttpServletRequestBuilder enabled(String rev,boolean value) { return auth(patch(path)).contentType("application/json").content("{\"expectedRevision\":\""+rev+"\",\"enabled\":"+value+"}"); }
    @Test void replacementEnableRemovalAndAbaProtection(CapturedOutput output) throws Exception {
        var empty=call(auth(get(path)),200);assertThat(empty.path("revision").asString()).isEqualTo("0");assertThat(empty.path("updatedAt").isNull()).isTrue();
        var saved=call(replacement("0").header("Idempotency-Key","same-test"),200);
        assertThat(saved.propertyNames()).containsExactlyInAnyOrder("configured","enabled","revision","updatedAt");
        assertThat(saved.path("configured").asBoolean()).isTrue();assertThat(saved.path("enabled").asBoolean()).isTrue();
        assertThat(saved.path("revision").asString()).isEqualTo("2");
        call(replacement("0").header("Idempotency-Key","same-test"),409);
        assertThat(call(enabled("2",false),200).path("enabled").asBoolean()).isFalse();
        assertThat(call(enabled("3",true),200).path("enabled").asBoolean()).isTrue();
        assertThat(call(replacement("4"),200).path("enabled").asBoolean()).isTrue();
        assertThat(call(auth(delete(path)).queryParam("expectedRevision","6"),200).path("configured").asBoolean()).isFalse();
        call(replacement("0"),409);call(enabled("7",true),409);call(replacement("7"),200);
        String row=owner.queryForObject("SELECT row_to_json(c)::text FROM assistant_model_configuration c WHERE project_id=?",String.class,data.runtime().projectId());
        assertThat(row).doesNotContain(secret,MASTER);
        String logs=owner.queryForObject("SELECT coalesce(json_agg(a)::text,'[]') FROM sys_audit_log a WHERE project_id=?",String.class,data.runtime().projectId());
        assertThat(logs).contains("assistant.model_configuration.replace").doesNotContain(secret,MASTER);
        assertThat(output.getAll()).doesNotContain(secret,MASTER);
    }
    @Test void rolesRevocationAndWrongSelectedProject() throws Exception {
        for(String role:List.of("OPERATOR","VIEWER")) { role(role);call(auth(get(path)),403);call(replacement("0"),403);call(enabled("0",false),403);call(auth(delete(path)).queryParam("expectedRevision","0"),403); }
        role("OWNER");call(replacement("0").header("Idempotency-Key","revoked-test"),200);
        var another=WebAppDataRuntimeFixture.seed(owner);
        call(auth(get(path.replace(data.runtime().projectId().toString(),another.runtime().projectId().toString()))),404);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",data.runtime().projectId(),data.runtime().actorId());
        var response=mvc.perform(replacement("0").header("Idempotency-Key","revoked-test")).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(401,404);
    }
    @Test void archivedReadableButWritesDeniedAndDeletedUnreadable() throws Exception {
        call(replacement("0"),200);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",data.runtime().projectId());
        call(auth(get(path)),200);call(enabled("1",false),429);call(replacement("1"),429);call(auth(delete(path)).queryParam("expectedRevision","1"),429);
        // 既有HTTP配额层对归档先拒绝；另证业务写守卫自身也拒绝，不能靠前置过滤器掩盖。
        var f=data.runtime();TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
        try { assertThatThrownBy(()->service.enable(f.projectId(),"1",false))
            .isInstanceOf(com.things.link.shared.error.BusinessException.class)
            .satisfies(e->assertThat(((com.things.link.shared.error.BusinessException)e).errorCode().httpStatus()).isEqualTo(403));
        } finally { TenantContext.clear(); }
        owner.update("UPDATE sys_project SET deleted_at=now() WHERE id=?",data.runtime().projectId());
        var response=mvc.perform(auth(get(path))).andReturn().getResponse();assertThat(response.getStatus()).isIn(401,404);
    }
    @Test void closedBodyDuplicateTrailingAndSecretErrorsNeverEcho(CapturedOutput output) throws Exception {
        for(String body:List.of("{\"expectedRevision\":\"0\",\"apiKey\":\""+secret+"\",\"url\":\"https://example.com\"}",
            "{\"expectedRevision\":0,\"apiKey\":\""+secret+"\"}",
            "{\"expectedRevision\":\"0\",\"apiKey\":\""+secret+"\",\"apiKey\":\"duplicate\"}",
            "{\"expectedRevision\":\"0\",\"apiKey\":\""+secret+"\"} {}",
            "{\"expectedRevision\":\"0\",\"apiKey\":\""+secret))
            call(auth(put(path)).contentType("application/json").content(body),400);
        call(replacement("0").queryParam("tenantId",UUID.randomUUID().toString()),400);
        call(auth(delete(path)).queryParam("expectedRevision","0","0"),400);
        assertThat(output.getAll()).doesNotContain(secret);
    }
    @Test void concurrentRevisionHasExactlyOneWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Integer> work=()->{ready.countDown();start.await();return mvc.perform(replacement("0")).andReturn().getResponse().getStatus();};
            var a=pool.submit(work);var b=pool.submit(work);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void auditFailureRollsBackCredentialAndRevision() throws Exception {
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit).record(argThat(e->e!=null && data.runtime().projectId().equals(e.projectId())));
        try { call(replacement("0"),500); } finally { reset(audit); }
        assertThat(call(auth(get(path)),200).path("revision").asString()).isEqualTo("0");
    }
    @Test void localVerificationFailureRollsBackInitialAndExistingCredential() throws Exception {
        doThrow(new IllegalStateException("synthetic verification failure")).when(cipher).verify(any());
        try { call(replacement("0"),500); } finally { reset(cipher); }
        assertThat(call(auth(get(path)),200).path("revision").asString()).isEqualTo("0");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'assistant.model_configuration.%'",Integer.class,data.runtime().projectId())).isZero();
        call(replacement("0"),200);
        var before=owner.queryForMap("SELECT revision,credential_revision,enabled,ciphertext,nonce FROM assistant_model_configuration WHERE project_id=?",data.runtime().projectId());
        doThrow(new IllegalStateException("synthetic verification failure")).when(cipher).verify(any());
        try { call(replacement("2"),500); } finally { reset(cipher); }
        var after=owner.queryForMap("SELECT revision,credential_revision,enabled,ciphertext,nonce FROM assistant_model_configuration WHERE project_id=?",data.runtime().projectId());
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'assistant.model_configuration.%'",Integer.class,data.runtime().projectId())).isEqualTo(2);
    }
    @Test void enableAuditFailureRollsBackBothTransitions() throws Exception {
        doThrow(new IllegalStateException("synthetic enable audit failure")).when(audit).record(argThat(e->e!=null && data.runtime().projectId().equals(e.projectId()) && "assistant.model_configuration.enable".equals(e.action())));
        try { call(replacement("0"),500); } finally { reset(audit); }
        assertThat(call(auth(get(path)),200).path("revision").asString()).isEqualTo("0");
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action LIKE 'assistant.model_configuration.%'",Integer.class,data.runtime().projectId())).isZero();
    }
    @Test void corruptCredentialCannotEnableButCanDisableAndRemove() throws Exception {
        call(replacement("0"),200);
        owner.update("UPDATE assistant_model_configuration SET ciphertext=decode(repeat('00',32),'hex'),credential_revision=revision+1,revision=revision+1,enabled=false WHERE project_id=?",data.runtime().projectId());
        assertThat(call(enabled("3",true),503).path("code").asInt()).isEqualTo(50061);
        call(enabled("3",false),200);call(auth(delete(path)).queryParam("expectedRevision","4"),200);
    }
    @Test void debugBodyConversionDoesNotLogCredential(CapturedOutput output) throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor");
        var before=logger.getLevel();
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            call(replacement("0"),200);
            call(auth(put(path)).contentType("application/json").content("{\"apiKey\":\""+secret),400);
            assertThat(events.list).isNotEmpty();
            assertThat(events.list).allSatisfy(event->assertThat(event.getFormattedMessage()).doesNotContain(secret));
            assertThat(output.getAll()).doesNotContain(secret);
        } finally { logger.detachAppender(events); events.stop(); logger.setLevel(before); }
    }
    @Test void databaseRejectsReinstallingAnEarlierCredentialVersion() throws Exception {
        call(replacement("0"),200);
        var old=owner.queryForMap("SELECT ciphertext,nonce,encryption_key_id FROM assistant_model_configuration WHERE project_id=?",data.runtime().projectId());
        call(replacement("2"),200);
        assertThatThrownBy(()->owner.update("""
            UPDATE assistant_model_configuration SET ciphertext=?,nonce=?,encryption_key_id=?,credential_revision=1,revision=revision+1
            WHERE project_id=?
            """,old.get("ciphertext"),old.get("nonce"),old.get("encryption_key_id"),data.runtime().projectId()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(call(auth(get(path)),200).path("revision").asString()).isEqualTo("4");
    }
    @Test void collaboratorUsesOwningTenantInsteadOfJwtTenant() throws Exception {
        UUID foreign=UUID.randomUUID();owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'agent collaborator')",foreign);
        var f=data.runtime();token=tokens.issue(new AuthenticatedPrincipal(f.actorId(),foreign,f.projectId())).value();
        call(replacement("0"),200);call(auth(get(path)),200);
        assertThat(owner.queryForObject("SELECT tenant_id FROM assistant_model_configuration WHERE project_id=?",UUID.class,f.projectId())).isEqualTo(f.tenantId());
    }
    @Test void rawAppRlsRejectsOtherProjectOrTenantAndOrdinaryDelete() throws Exception {
        call(replacement("0"),200);var f=data.runtime();
        try {
            TenantContext.set(new TenantScope(f.tenantId(),UUID.randomUUID(),f.actorId()));
            assertThat(application.queryForObject("SELECT count(*) FROM assistant_model_configuration",Integer.class)).isZero();
            TenantContext.set(new TenantScope(UUID.randomUUID(),f.projectId(),f.actorId()));
            assertThat(application.queryForObject("SELECT count(*) FROM assistant_model_configuration",Integer.class)).isZero();
            TenantContext.set(new TenantScope(f.tenantId(),f.projectId(),f.actorId()));
            assertThat(application.queryForObject("SELECT count(*) FROM assistant_model_configuration",Integer.class)).isEqualTo(1);
            assertThatThrownBy(()->application.update("DELETE FROM assistant_model_configuration WHERE project_id=?",f.projectId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { TenantContext.clear(); }
    }
}
