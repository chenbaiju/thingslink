package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 独占真实库验证跨项目换签、扫描边界及失败原子性。 */
@AutoConfigureMockMvc
@Import(PushInstallationApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"INSTALLATION_POSTGRES"})
class PushInstallationApiTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> INSTALLATION_POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_push_installation").withUsername("thingslink").withPassword("thingslink");
    private static final String DATABASE_URL=startDatabase();
    private static final ObjectMapper JSON=new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate application;
    @MockitoBean(enforceOverride=true,name="relaxRestQuota") ApplicationRunner unusedSharedQuotaRunner;
    private JdbcTemplate owner;
    @BeforeEach void setup(){owner=ownerJdbc();assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);}

    @Test void rotatesWithCasAndLogoutPreventsOldAccessRegistration() throws Exception {
        var f=fixture();var a=login(f);UUID install=Uuid7.generate();
        var first=ok(register(a,install,"token-a","0"));assertThat(first.path("revision").asString()).isEqualTo("1");
        error(register(a,install,"token-b","0"),409,60065);
        var next=ok(register(a,install,"token-b","1"));assertThat(next.path("bindingId").asString()).isEqualTo(first.path("bindingId").asString());
        assertThat(owner.queryForObject("SELECT count(*) FROM app_push_token WHERE app_user_id=? AND status='ACTIVE'",Integer.class,f.user())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM app_push_token WHERE app_user_id=?",Integer.class,f.user())).isEqualTo(2);
        logout(a);error(register(a,install,"token-c","2"),401,60009);
        assertThat(eligible(f,install)).isFalse();
        var fresh=login(f);var replaced=ok(register(fresh,install,"token-c","2"));
        assertThat(replaced.path("bindingId").asString()).isNotEqualTo(first.path("bindingId").asString());
    }
    @Test void crossProjectPreservesGroupAndLateOldLogoutCannotRevokeSuccessor()throws Exception {
        var f=fixture();var a=login(f);UUID install=Uuid7.generate();ok(register(a,install,"switch-token","0"));
        var switched=ok(switchTo(a,project(f,true))).path("session");
        assertThat(switched.path("identity").path("sessionGroupId").asString()).isEqualTo(a.path("identity").path("sessionGroupId").asString());
        assertThat(switched.path("identity").path("sessionId").asString()).isNotEqualTo(a.path("identity").path("sessionId").asString());
        logout(a);assertThat(eligible(f,install)).isTrue();
        error(register(a,install,"stale-token","1"),401,60009);
        ok(register(switched,install,"new-token","1"));logout(switched);assertThat(eligible(f,install)).isFalse();
    }
    @Test void globalTokenMovesWithoutCrossTenantRowsOrPlaintext()throws Exception {
        var f=fixture();var g=fixture();var a=login(f);var other=login(g);UUID install=Uuid7.generate(),second=Uuid7.generate();
        ok(register(a,install,"shared-opaque-token","0"));ok(register(other,second,"shared-opaque-token","0"));
        assertThat(eligible(f,install)).isFalse();assertThat(eligible(g,second)).isTrue();
        assertThat(owner.queryForObject("SELECT status FROM app_push_token WHERE app_user_id=?",String.class,f.user())).isEqualTo("ACTIVE");
        assertThat(application.queryForObject("SELECT count(*) FROM app_push_token",Integer.class)).isZero();
        var own=ok(mvc.perform(get("/api/v1/app/push-installations/"+install).header("Authorization","Bearer "+a.path("accessToken").asString())).andReturn());
        assertThat(own.path("status").asString()).isEqualTo("REVOKED");assertThat(own.toString()).doesNotContain("shared-opaque-token","token_digest","token_cipher");
        error(mvc.perform(get("/api/v1/app/push-installations/"+install).header("Authorization","Bearer "+other.path("accessToken").asString())).andReturn(),404,60066);
    }
    @Test void frozenProjectAllowsOnlyExactSecurityRevocationAndLeaseExpires()throws Exception {
        var f=fixture();var a=login(f);UUID install=Uuid7.generate();ok(register(a,install,"freeze-token","0"));
        owner.update("UPDATE app_push_token SET lease_expires_at=now()-interval '1 second' WHERE app_user_id=?",f.user());assertThat(eligible(f,install)).isFalse();
        ok(register(a,install,"freeze-token","1"));owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",f.project());
        assertThat(register(a,install,"blocked","2").getResponse().getStatus()).isEqualTo(403);
        error(remove(a,install,"1"),409,60065);assertThat(remove(a,install,"2").getResponse().getStatus()).isEqualTo(204);
        assertThat(remove(a,install,"2").getResponse().getStatus()).isEqualTo(204);assertThat(eligible(f,install)).isFalse();
    }
    @Test void registrationCannotChooseGroupAndPasswordRevokesAllBindings()throws Exception {
        var f=fixture();var a=login(f);var second=login(f);UUID install=Uuid7.generate();ok(register(a,install,"password-token","0"));ok(register(second,Uuid7.generate(),"password-token-2","0"));
        var changed=mvc.perform(post("/api/v1/app/auth/password").header("Authorization","Bearer "+a.path("accessToken").asString()).contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("oldPassword","test-password").put("newPassword","new-test-password").toString())).andReturn();
        assertThat(changed.getResponse().getStatus()).as(changed.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(eligible(f,install)).isFalse();error(register(second,install,"late","1"),401,60009);
    }
    @Test void concurrentCasHasOneWinnerAndRejectsLegacyRealProvider()throws Exception {
        var f=fixture();var a=login(f);UUID install=Uuid7.generate();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(()->{start.await();return register(a,install,"concurrent-a","0");});
            var two=pool.submit(()->{start.await();return register(a,install,"concurrent-b","0");});start.countDown();
            assertThat(List.of(one.get(20,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus(),two.get(20,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus())).containsExactlyInAnyOrder(200,409);
        }finally{pool.shutdownNow();}
        error(mvc.perform(put("/api/v1/app/push-tokens").header("Authorization","Bearer "+a.path("accessToken").asString()).contentType(MediaType.APPLICATION_JSON)
            .content(JSON.createObjectNode().put("installationId",install.toString()).put("provider","APNS").put("token","unbound").toString())).andReturn(),503,60067);
    }
    @Autowired com.things.link.alarm.application.AlarmPushAudiencePort audience;
    @Autowired com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort authorization;
    @Autowired com.things.link.enduser.application.PushInstallationConfiguration configuration;
    @Test void snapshotsAndPresendRecheckRevisionSessionLeaseAndChannelIdentity()throws Exception {
        var f=fixture();var a=login(f);UUID install=Uuid7.generate(),type=Uuid7.generate(),device=Uuid7.generate();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES(?,?,?,'push','测试','STANDARD','DIRECT','PUBLISHED')",type,f.tenant(),f.project());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES(?,?,?,?,'push','测试','ONLINE')",device,f.tenant(),f.project(),type);
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role,status) VALUES(?,?,?,?,?,'PRIMARY','ACTIVE')",Uuid7.generate(),f.tenant(),f.project(),f.user(),device);
        ok(register(a,install,"first-sensitive-token","0"));
        UUID legacy=Uuid7.generate();
        owner.update("INSERT INTO app_push_token(id,tenant_id,app_user_id,installation_id,provider,token_cipher,token_nonce,key_id,status) "
                +"SELECT ?,tenant_id,app_user_id,?,'APNS',token_cipher,token_nonce,key_id,'ACTIVE' FROM app_push_token WHERE app_user_id=?",legacy,Uuid7.generate(),f.user());
        assertThat(transaction(f,()->audience.listActiveInstallations(f.tenant(),f.project(),device))).hasSize(1);
        assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),legacy))).isEmpty();
        UUID first=transaction(f,()->audience.listActiveInstallations(f.tenant(),f.project(),device).getFirst().pushTokenId());
        assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),first))).isPresent();
        ok(register(a,install,"rotated-sensitive-token","1"));
        UUID next=transaction(f,()->audience.listActiveInstallations(f.tenant(),f.project(),device).getFirst().pushTokenId());assertThat(next).isNotEqualTo(first);
        assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),first))).isEmpty();
        var c=configuration.channels().get("test");
        try {
            configuration.channels().put("test",new com.things.link.enduser.application.PushInstallationConfiguration.Channel(c.provider(),"other.application",c.environment(),true));
            assertThat(transaction(f,()->audience.listActiveInstallations(f.tenant(),f.project(),device))).isEmpty();
            assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),next))).isEmpty();
        }finally{configuration.channels().put("test",c);}
        assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),next))).isPresent();
        logout(a);assertThat(transaction(f,()->audience.listActiveInstallations(f.tenant(),f.project(),device))).isEmpty();
        assertThat(transaction(f,()->authorization.authorize(f.tenant(),f.project(),device,f.user(),next))).isEmpty();
    }
    private <T> T transaction(Fixture f,java.util.function.Supplier<T> action) {
        return new org.springframework.transaction.support.TransactionTemplate(tx).execute(s->{scope.establish(f.tenant(),f.project());return action.get();});
    }
    @Autowired com.things.link.enduser.application.PushInstallationRepository bindings;
    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired com.things.link.support.tenant.TransactionLocalRlsScope scope;
    private boolean eligible(Fixture f,UUID install) {
        return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(tx).execute(s->{scope.establish(f.tenant(),f.project());return bindings.eligible(f.tenant(),f.user(),bindings.latest(f.tenant(),f.user(),install).orElseThrow().id());}));
    }
    private MvcResult register(JsonNode a,UUID install,String token,String revision)throws Exception {
        return mvc.perform(put("/api/v1/app/push-installations").header("Authorization","Bearer "+a.path("accessToken").asString()).contentType(MediaType.APPLICATION_JSON)
            .content(JSON.createObjectNode().put("installationId",install.toString()).put("registrationId",Uuid7.generate().toString()).put("channelConfigurationId","test").put("providerToken",token).put("expectedRevision",revision).toString())).andReturn();
    }
    private MvcResult remove(JsonNode a,UUID install,String revision)throws Exception {return mvc.perform(delete("/api/v1/app/push-installations/"+install).header("Authorization","Bearer "+a.path("accessToken").asString()).header("If-Match","\""+revision+"\"")).andReturn();}
    private void logout(JsonNode a)throws Exception {assertThat(mvc.perform(post("/api/v1/app/auth/logout").contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("refreshToken",a.path("refreshToken").asString()).toString())).andReturn().getResponse().getStatus()).isEqualTo(204);}

    private Fixture fixture(){
        UUID tenant=Uuid7.generate(),user=Uuid7.generate(),account=Uuid7.generate();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES(?,'导航测试')",tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES(?,?,'unused','测试',now())",account,account+"@example.test");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES(?,?,?)",Uuid7.generate(),tenant,account);
        owner.update("INSERT INTO app_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,'导航用户',?)",user,tenant,user.toString(),passwords.encode("test-password"));
        var partial=new Fixture(tenant,null,account,user);return new Fixture(tenant,project(partial,true),account,user);
    }
    private UUID project(Fixture f,boolean visible){UUID id=Uuid7.generate();
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'导航项目',?)",id,f.tenant(),"nav_"+id.toString().replace("-",""));
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OWNER')",Uuid7.generate(),id,f.account());
        if(visible)owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES(?,?,?,?,'OBSERVER')",Uuid7.generate(),f.tenant(),id,f.user());return id;
    }
    private JsonNode login(Fixture f)throws Exception{return ok(mvc.perform(post("/api/v1/app/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("projectKey","nav_"+f.project().toString().replace("-","")).put("username",f.user().toString()).put("password","test-password").toString())).andReturn());}
    private MvcResult switchTo(JsonNode session,UUID target)throws Exception{return mvc.perform(post("/api/v1/app/auth/switch-project").header("Authorization","Bearer "+session.path("accessToken").asString()).contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("refreshToken",session.path("refreshToken").asString()).put("targetProjectId",target.toString()).toString())).andReturn();}
    private MvcResult refresh(JsonNode session)throws Exception{return mvc.perform(post("/api/v1/app/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("refreshToken",session.path("refreshToken").asString()).toString())).andReturn();}
    private static JsonNode ok(MvcResult result)throws Exception{assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);return JSON.readTree(result.getResponse().getContentAsString());}
    private static void error(MvcResult result,int status,int code)throws Exception{assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(code);}
    private static JdbcTemplate ownerJdbc(){return new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL,INSTALLATION_POSTGRES.getUsername(),INSTALLATION_POSTGRES.getPassword()));}
    private static String startDatabase(){INSTALLATION_POSTGRES.start();return INSTALLATION_POSTGRES.getJdbcUrl();}
    @TestConfiguration(proxyBeanMethods=false) static class IsolatedDatabaseConfiguration {
        @Bean DynamicPropertyRegistrar navigationDatabase(){// 两个并发外层事务加一次独立RLS复验需要第三连接；仅本类独占库提供余量。
            return registry->{registry.add("things-link.app-push-installations.digest-key",()->"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");registry.add("things-link.app-push-installations.channels.test.provider",()->"MOCK");registry.add("things-link.app-push-installations.channels.test.application-id",()->"io.thingslink.flutter");registry.add("things-link.app-push-installations.channels.test.environment",()->"test");registry.add("things-link.app-push-installations.channels.test.enabled",()->"true");registry.add("things-link.datasource.control.maximum-pool-size",()->"4");registry.add("things-link.app-navigation.backend-instance-id",()->"56f43e3e-97d8-438d-82c1-b401c8201731");registry.add("spring.datasource.url",()->DATABASE_URL);registry.add("spring.flyway.url",()->DATABASE_URL);registry.add("spring.flyway.user",INSTALLATION_POSTGRES::getUsername);registry.add("spring.flyway.password",INSTALLATION_POSTGRES::getPassword);registry.add("things-link.outbox.publisher.enabled",()->"false");registry.add("spring.kafka.listener.auto-startup",()->"false");registry.add("things-link.notification.retry.enabled",()->"false");};}
        @Bean ApplicationRunner navigationQuota(){return args->ownerJdbc().update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");}
    }
    private record Fixture(UUID tenant,UUID project,UUID account,UUID user){}
}
