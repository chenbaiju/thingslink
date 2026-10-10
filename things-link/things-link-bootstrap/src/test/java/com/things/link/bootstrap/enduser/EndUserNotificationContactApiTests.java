package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 独占数据库验证号码隐私、双端身份、CAS与审计回滚，不修改共享环境。 */
@AutoConfigureMockMvc
@Import(EndUserNotificationContactApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"CONTACT_POSTGRES"})
class EndUserNotificationContactApiTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> CONTACT_POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("notification_contact_api").withUsername("thingslink").withPassword("thingslink");
    private static final String DATABASE_URL=startDatabase();
    private static final ObjectMapper JSON=new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate application;
    @MockitoBean(enforceOverride=true,name="relaxRestQuota") ApplicationRunner unusedSharedQuotaRunner;
    private JdbcTemplate owner;
    @BeforeEach void setup(){owner=ownerJdbc();assertThat(application.queryForObject("SELECT current_database()",String.class)).isEqualTo("notification_contact_api");assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);}

    @ParameterizedTest @EnumSource(value=ProjectRole.class,names={"OWNER","ADMIN"})
    void managerConfiguresOnlyCurrentProjectAndAppReadsWithoutEditing(ProjectRole role)throws Exception{
        var f=fixture(role);String path=path(f);
        var empty=ok(console(f,get(path)));assertThat(empty.path("voiceNumber").isNull()).isTrue();assertThat(empty.path("revision").asString()).isEqualTo("0");
        var saved=ok(console(f,put(path).contentType(MediaType.APPLICATION_JSON).content(body("0","+8613800000001","+8613800000002"))));
        assertThat(saved.path("revision").asString()).isEqualTo("1");
        var appToken=appToken(f);var channel=ok(mvc.perform(get("/api/v1/app/account/notification-channels").header("Authorization","Bearer "+appToken)).andReturn());
        assertThat(channel.path("voiceNumber").asString()).isEqualTo("+8613800000001");assertThat(channel.path("smsNumber").asString()).isEqualTo("+8613800000002");
        assertThat(channel.path("voiceAvailable").asBoolean()).isFalse();assertThat(channel.path("smsAvailable").asBoolean()).isFalse();assertThat(channel.has("quotaRemaining")).isFalse();
        assertThat(mvc.perform(put("/api/v1/app/account/notification-channels").header("Authorization","Bearer "+appToken).contentType(MediaType.APPLICATION_JSON).content(body("1",null,null))).andReturn().getResponse().getStatus()).isEqualTo(405);
        var other=new Fixture(f.tenant(),Uuid7.generate(),f.account(),f.user());insertProject(other,"OWNER");
        assertThat(ok(console(other,get(path(other)))).path("voiceNumber").isNull()).isTrue();
        var otherToken=appToken(other);assertThat(ok(mvc.perform(get("/api/v1/app/account/notification-channels").header("Authorization","Bearer "+otherToken).param("projectId",f.project().toString())).andReturn()).path("smsNumber").isNull()).isTrue();
        error(console(f,put(path).contentType(MediaType.APPLICATION_JSON).content(body("0",null,null))),409,60062);
        assertThat(ok(console(f,put(path).contentType(MediaType.APPLICATION_JSON).content(body("1","+8613800000001","+8613800000002")))).path("revision").asString()).isEqualTo("1");
        String audit=owner.queryForObject("SELECT details::text FROM sys_audit_log WHERE project_id=? AND action='END_USER_NOTIFICATION_CONTACT_UPDATED'",String.class,f.project());
        assertThat(audit).doesNotContain("13800000001","13800000002").contains("voiceConfigured");
        assertThat(ok(console(f,put(path).contentType(MediaType.APPLICATION_JSON).content(body("1",null,null)))).path("revision").asString()).isEqualTo("2");
        assertThat(ok(console(f,get(path))).path("voiceNumber").isNull()).isTrue();
    }
    @ParameterizedTest @EnumSource(value=ProjectRole.class,names={"OPERATOR","VIEWER"})
    void ordinaryMembersCannotReadOrChangeContact(ProjectRole role)throws Exception{
        var f=fixture(role);error(console(f,get(path(f))),403,60002);error(console(f,put(path(f)).contentType(MediaType.APPLICATION_JSON).content(body("0",null,null))),403,60002);
    }
    @Test void archivedCrossScopeAndStaleRoleAreDenied()throws Exception{
        var f=fixture(ProjectRole.OWNER);var other=fixture(ProjectRole.OWNER);
        error(console(f,get(path(f).replace(f.user().toString(),other.user().toString()))),404,60005);
        error(console(f,get(path(other))),404,50001);
        String token=appToken(f);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",f.project());
        assertThat(ok(console(f,get(path(f)))).path("revision").asString()).isEqualTo("0");
        error(console(f,put(path(f)).contentType(MediaType.APPLICATION_JSON).content(body("0","+8613800000001",null))),403,50017);
        owner.update("UPDATE app_user_role SET status='DISABLED' WHERE project_id=? AND app_user_id=?",f.project(),f.user());
        error(mvc.perform(get("/api/v1/app/account/notification-channels").header("Authorization","Bearer "+token)).andReturn(),401,60009);
    }
    @Test void malformedNumbersAndEnvelopeDoNotPersist()throws Exception{
        var f=fixture(ProjectRole.OWNER);
        for(String request:List.of(body("0","13800000001",null),body("0","+861380000000123456",null),body("0","+86 x",null),"{}",
                "{\"voiceNumber\":null,\"smsNumber\":null,\"expectedRevision\":\"0\",\"appPushEnabled\":true}",
                "{\"voiceNumber\":null,\"voiceNumber\":null,\"smsNumber\":null,\"expectedRevision\":\"0\"}")) {
            error(console(f,put(path(f)).contentType(MediaType.APPLICATION_JSON).content(request)),400,10001);
        }
        assertThat(ok(console(f,get(path(f)))).path("revision").asString()).isEqualTo("0");
    }
    @Test void auditFailureRollsBackContact()throws Exception{
        var f=fixture(ProjectRole.OWNER);
        owner.execute("ALTER TABLE sys_audit_log ADD CONSTRAINT contact_test_failure CHECK (action<>'END_USER_NOTIFICATION_CONTACT_UPDATED') NOT VALID");
        try{
            assertThat(console(f,put(path(f)).contentType(MediaType.APPLICATION_JSON).content(body("0","+8613800000001",null))).getResponse().getStatus()).isEqualTo(500);
            assertThat(ok(console(f,get(path(f)))).path("revision").asString()).isEqualTo("0");
        }finally{owner.execute("ALTER TABLE sys_audit_log DROP CONSTRAINT contact_test_failure");}
    }
    private Fixture fixture(ProjectRole role){
        var f=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES(?,'联系配置租户')",f.tenant());insertAccount(f.tenant(),f.account());
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES(?,?,?,?)",f.user(),f.tenant(),f.user().toString(),passwords.encode("test-password"));
        insertProject(f,role.name());return f;
    }
    private void insertProject(Fixture f,String role){
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'联系配置项目',?)",f.project(),f.tenant(),key(f));
        UUID ownerId=role.equals("OWNER")?f.account():Uuid7.generate();if(!ownerId.equals(f.account()))insertAccount(f.tenant(),ownerId);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OWNER')",Uuid7.generate(),f.project(),ownerId);
        if(!ownerId.equals(f.account()))owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,?)",Uuid7.generate(),f.project(),f.account(),role);
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES(?,?,?,?,'OBSERVER')",Uuid7.generate(),f.tenant(),f.project(),f.user());
    }
    private void insertAccount(UUID tenant,UUID account){owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES(?,?,'test-only-unusable-hash','管理者',now())",account,account+"@example.test");owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES(?,?,?)",Uuid7.generate(),tenant,account);}
    private String appToken(Fixture f)throws Exception{return ok(mvc.perform(post("/api/v1/app/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("projectKey",key(f)).put("username",f.user().toString()).put("password","test-password").toString())).andReturn()).path("accessToken").asString();}
    private MvcResult console(Fixture f,MockHttpServletRequestBuilder call)throws Exception{return mvc.perform(call.header("Authorization","Bearer "+tokens.issue(new AuthenticatedPrincipal(f.account(),f.tenant(),f.project())).value())).andReturn();}
    private static String path(Fixture f){return "/api/v1/projects/"+f.project()+"/end-users/"+f.user()+"/notification-contact";}
    private static String key(Fixture f){return "contact_"+f.project().toString().replace("-","");}
    private static String body(String revision,String voice,String sms){var node=JSON.createObjectNode().put("expectedRevision",revision);if(voice==null)node.putNull("voiceNumber");else node.put("voiceNumber",voice);if(sms==null)node.putNull("smsNumber");else node.put("smsNumber",sms);return node.toString();}
    private static JsonNode ok(MvcResult result)throws Exception{assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);return JSON.readTree(result.getResponse().getContentAsString());}
    private static void error(MvcResult result,int status,int code)throws Exception{assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(code);}
    private static JdbcTemplate ownerJdbc(){return new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL,CONTACT_POSTGRES.getUsername(),CONTACT_POSTGRES.getPassword()));}
    private static String startDatabase(){CONTACT_POSTGRES.start();return CONTACT_POSTGRES.getJdbcUrl();}
    @TestConfiguration(proxyBeanMethods=false) static class IsolatedDatabaseConfiguration {
        @Bean DynamicPropertyRegistrar contactDatabase(){return registry->{registry.add("spring.datasource.url",()->DATABASE_URL);registry.add("spring.flyway.url",()->DATABASE_URL);registry.add("spring.flyway.user",CONTACT_POSTGRES::getUsername);registry.add("spring.flyway.password",CONTACT_POSTGRES::getPassword);registry.add("things-link.outbox.publisher.enabled",()->"false");registry.add("spring.kafka.listener.auto-startup",()->"false");registry.add("things-link.notification.retry.enabled",()->"false");};}
        @Bean ApplicationRunner contactQuota(){return args->ownerJdbc().update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");}
    }
    private record Fixture(UUID tenant,UUID project,UUID account,UUID user){}
}
