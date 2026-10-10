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
@Import(AppProjectNavigationApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"NAVIGATION_POSTGRES"})
class AppProjectNavigationApiTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> NAVIGATION_POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_project_navigation").withUsername("thingslink").withPassword("thingslink");
    private static final String DATABASE_URL=startDatabase();
    private static final ObjectMapper JSON=new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate application;
    @MockitoBean(enforceOverride=true,name="relaxRestQuota") ApplicationRunner unusedSharedQuotaRunner;
    private JdbcTemplate owner;
    @BeforeEach void setup(){owner=ownerJdbc();assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);}

    @Test void discoversAndSwitchesAtomicallyWithoutReusingSourceFamily()throws Exception {
        var f=fixture();var a=login(f);UUID target=project(f,true);
        var page=ok(mvc.perform(get("/api/v1/app/projects").header("Authorization","Bearer "+a.path("accessToken").asString())).andReturn());
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("appUserId").asString()).isEqualTo(f.user().toString());
        var result=ok(switchTo(a,target));var next=result.path("session");
        assertThat(result.path("projectId").asString()).isEqualTo(target.toString());
        error(refresh(a),401,60007);
        assertThat(ok(mvc.perform(get("/api/v1/app/account").header("Authorization","Bearer "+next.path("accessToken").asString())).andReturn()).path("id").asString()).isEqualTo(f.user().toString());
        // 迟到源退出只触及旧族，不能撤销目标新族。
        mvc.perform(post("/api/v1/app/auth/logout").contentType(MediaType.APPLICATION_JSON).content(JSON.createObjectNode().put("refreshToken",a.path("refreshToken").asString()).toString()));
        ok(refresh(next));
        assertThat(application.queryForObject("SELECT count(*) FROM app_user_role",Integer.class)).isZero();
    }
    @Test void rejectsInvisibleTargetsWithoutConsumingSource()throws Exception {
        var f=fixture();var a=login(f);UUID absent=project(f,false);var other=fixture();
        for(UUID target:List.of(absent,other.project(),Uuid7.generate()))error(switchTo(a,target),404,60064);
        UUID frozen=project(f,true);owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",frozen);
        error(switchTo(a,frozen),404,60064);ok(refresh(a));
    }
    @Test void cannotMixAccessAndRefreshFromDifferentUsers()throws Exception {
        var f=fixture();var a=login(f);var other=login(fixture());UUID target=project(f,true);
        var mixed=JSON.createObjectNode().put("accessToken",a.path("accessToken").asString()).put("refreshToken",other.path("refreshToken").asString());
        error(switchTo(mixed,target),401,60007);ok(refresh(a));ok(refresh(other));
    }
    @Test void revokedSourceRoleAndReusedRefreshCannotSignTarget()throws Exception {
        var f=fixture();var a=login(f);UUID target=project(f,true);
        owner.update("UPDATE app_user_role SET status='DISABLED' WHERE project_id=? AND app_user_id=?",f.project(),f.user());
        error(switchTo(a,target),401,60007);
        owner.update("UPDATE app_user_role SET status='ACTIVE' WHERE project_id=? AND app_user_id=?",f.project(),f.user());
        var rotated=ok(refresh(a));error(switchTo(a,target),401,60007);error(refresh(rotated),401,60007);
    }
    @Test void emptyScannedPageStillAdvancesAndCursorBindsIdentity()throws Exception {
        var f=fixture();var a=login(f);
        // 将源项目放到扫描末尾，前100条均没有角色。
        UUID lowTenant=f.tenant();
        for(int i=1;i<=101;i++) {
            UUID id=UUID.fromString(String.format("00000000-0000-4000-8000-%012d",i));
            owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,?,?)",id,lowTenant,"不可见候选","scan_"+lowTenant+"_"+i);
        }
        var first=ok(mvc.perform(get("/api/v1/app/projects").header("Authorization","Bearer "+a.path("accessToken").asString())).andReturn());
        assertThat(first.path("items").size()).isZero();String cursor=first.path("nextCursor").asString();assertThat(cursor).isNotBlank();
        error(mvc.perform(get("/api/v1/app/projects").param("cursor",cursor+"x").header("Authorization","Bearer "+a.path("accessToken").asString())).andReturn(),400,10001);
        var next=ok(mvc.perform(get("/api/v1/app/projects").param("cursor",cursor).header("Authorization","Bearer "+a.path("accessToken").asString())).andReturn());
        assertThat(next.path("items").size()).isEqualTo(1);
        var other=login(fixture());error(mvc.perform(get("/api/v1/app/projects").param("cursor",cursor).header("Authorization","Bearer "+other.path("accessToken").asString())).andReturn(),400,10001);
    }
    @Test void failureAfterTargetInsertRollsBackBothFamilies()throws Exception {
        var f=fixture();var a=login(f);UUID target=project(f,true);
        // 仅本类独占库中新插入的目标刷新事实触发失败，验证源族保持可用。
        owner.execute("ALTER TABLE app_refresh_token ADD CONSTRAINT navigation_failure CHECK (project_id <> '"+target+"'::uuid) NOT VALID");
        try{assertThat(switchTo(a,target).getResponse().getStatus()).isEqualTo(500);}finally{owner.execute("ALTER TABLE app_refresh_token DROP CONSTRAINT navigation_failure");}
        ok(refresh(a));
    }
    @Test void invalidRefreshCannotProbeTargetExistence()throws Exception {
        var f=fixture();var a=login(f);var invalid=JSON.createObjectNode().put("accessToken",a.path("accessToken").asString()).put("refreshToken","invalid-test-token");
        error(switchTo(invalid,project(f,false)),401,60007);error(switchTo(invalid,Uuid7.generate()),401,60007);
    }
    @Test void concurrentRefreshAndSwitchNeverCreateTwoUsableSuccessors()throws Exception {
        var f=fixture();var a=login(f);UUID target=project(f,true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return switchTo(a,target);});
            var two=pool.submit(()->{start.await();return refresh(a);});start.countDown();
            var switched=one.get(15,java.util.concurrent.TimeUnit.SECONDS);var refreshed=two.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(switched.getResponse().getStatus(),refreshed.getResponse().getStatus())).containsExactlyInAnyOrder(200,401);
            assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NULL AND replaced_by IS NULL",Integer.class,f.user())).isLessThanOrEqualTo(1);
        }finally{pool.shutdownNow();}
    }

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
    private static JdbcTemplate ownerJdbc(){return new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL,NAVIGATION_POSTGRES.getUsername(),NAVIGATION_POSTGRES.getPassword()));}
    private static String startDatabase(){NAVIGATION_POSTGRES.start();return NAVIGATION_POSTGRES.getJdbcUrl();}
    @TestConfiguration(proxyBeanMethods=false) static class IsolatedDatabaseConfiguration {
        @Bean DynamicPropertyRegistrar navigationDatabase(){// 两个并发外层事务加一次独立RLS复验需要第三连接；仅本类独占库提供余量。
            return registry->{registry.add("things-link.datasource.control.maximum-pool-size",()->"4");registry.add("things-link.app-navigation.backend-instance-id",()->"56f43e3e-97d8-438d-82c1-b401c8201731");registry.add("spring.datasource.url",()->DATABASE_URL);registry.add("spring.flyway.url",()->DATABASE_URL);registry.add("spring.flyway.user",NAVIGATION_POSTGRES::getUsername);registry.add("spring.flyway.password",NAVIGATION_POSTGRES::getPassword);registry.add("things-link.outbox.publisher.enabled",()->"false");registry.add("spring.kafka.listener.auto-startup",()->"false");registry.add("things-link.notification.retry.enabled",()->"false");};}
        @Bean ApplicationRunner navigationQuota(){return args->ownerJdbc().update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");}
    }
    private record Fixture(UUID tenant,UUID project,UUID account,UUID user){}
}
