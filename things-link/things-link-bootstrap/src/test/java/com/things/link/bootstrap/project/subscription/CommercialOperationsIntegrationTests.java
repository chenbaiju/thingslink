package com.things.link.bootstrap.project.subscription;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.CommercialOperationsService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** ADR0164：真实Console认证、应用数据库角色、受控数据库身份及锁竞争。 */
@AutoConfigureMockMvc
class CommercialOperationsIntegrationTests extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;
    @Autowired CommercialOperationsService operations;
    @Autowired TenantResourcePackageService packages;
    @Autowired TransactionTemplate tx;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final List<Session> sessions=new ArrayList<>();
    private record Session(UUID account,UUID tenant,String token) { }
    @AfterEach void cleanup() throws Exception {
        TenantContext.clear();
        for (Session s:sessions) {
            manage(Uuid7.generate(),s.account(),false,"测试结束撤销");
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id=?",s.tenant());
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id=?",s.tenant());
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id=?",s.tenant());
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id=?",s.tenant());
            jdbc.update("DELETE FROM sys_account WHERE id=?",s.account());
            jdbc.update("DELETE FROM sys_tenant WHERE id=?",s.tenant());
        }
    }
    @Test void httpContractPublishesSuccessBodiesAndExactRequiredStrings() throws Exception {
        JsonNode spec=tree(mvc.perform(get("/v3/api-docs")).andReturn());
        String base="/api/v1/operations/tenants/{tenantId}/adjustments";
        for(String suffix:List.of("/context","/by-key/{key}"))
            assertThat(spec.get("paths").get(base+suffix).get("get").get("responses").has("200")).isTrue();
        assertThat(spec.get("paths").get(base).get("post").get("responses").get("200").has("content")).isTrue();
        JsonNode request=spec.get("components").get("schemas").get("CreateCommercialAdjustmentRequest");
        assertThat(request.get("properties").get("amount").get("type").asString()).isEqualTo("string");
        assertThat(request.get("required").toString()).contains("amount","expectedAssignmentVersion","idempotencyKey");
    }
    @Test void platformMenuAndPermissionsFollowDatabaseWithoutProjectSelection() throws Exception {
        Session s=session();
        String menu="/api/v1/system/menus", me="/api/v1/auth/me";
        assertThat(mvc.perform(get(menu).header("Authorization","Bearer "+s.token())).andReturn().getResponse().getContentAsString()).doesNotContain("CommercialOperations");
        assertThat(mvc.perform(get(me).header("Authorization","Bearer "+s.token())).andReturn().getResponse().getContentAsString()).doesNotContain("commercial:adjust");
        manage(Uuid7.generate(),s.account(),true,"平台菜单验收");
        var menus=mvc.perform(get(menu).header("Authorization","Bearer "+s.token())).andReturn();
        assertThat(menus.getResponse().getStatus()).isEqualTo(200);
        assertThat(menus.getResponse().getContentAsString()).contains("CommercialOperations");
        assertThat(mvc.perform(get(me).header("Authorization","Bearer "+s.token())).andReturn().getResponse().getContentAsString()).contains("commercial:adjust");
        manage(Uuid7.generate(),s.account(),false,"撤销立即反馈");
        assertThat(mvc.perform(get(menu).header("Authorization","Bearer "+s.token())).andReturn().getResponse().getContentAsString()).doesNotContain("CommercialOperations");
        assertThat(mvc.perform(get(me).header("Authorization","Bearer "+s.token())).andReturn().getResponse().getContentAsString()).doesNotContain("commercial:adjust");
    }
    @Test void ownerIsNotOperatorAndUnauthorizedCannotProbeTarget() throws Exception {
        Session s=session();
        assertThat(context(s,UUID.randomUUID()).getResponse().getStatus()).isEqualTo(403);
        assertThat(context(s,s.tenant()).getResponse().getContentAsString()).contains("50051");
        assertThat(mvc.perform(get(path(s.tenant())+"/context")).andReturn().getResponse().getStatus()).isEqualTo(401);
        manage(Uuid7.generate(),s.account(),true,"平台授权测试");
        assertThat(context(s,s.tenant()).getResponse().getStatus()).isEqualTo(200);
        jdbc.update("UPDATE sys_account SET status='LOCKED' WHERE id=?",s.account());
        assertThat(context(s,s.tenant()).getResponse().getStatus()).isIn(401,403);
    }
    @Test void grantUsesAuthenticatedActorAndExactQuantityWithReplayAndVersionChecks() throws Exception {
        Session actor=session(), target=session();
        manage(Uuid7.generate(),actor.account(),true,"跨租户审批");
        String version=tree(context(actor,target.tenant())).get("assignmentVersion").asString();
        Map<String,Object> input=input(version,"9007199254740993");
        MvcResult created=create(actor,target.tenant(),input);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode fact=tree(created);
        assertThat(fact.get("amount").asString()).isEqualTo("9007199254740993");
        assertThat(fact.get("operatorId").asString()).isEqualTo(actor.account().toString());
        assertThat(tree(create(actor,target.tenant(),input)).get("id").asString()).isEqualTo(fact.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.adjustment.granted'",Integer.class,target.tenant())).isEqualTo(1);
        Map<String,Object> changed=new LinkedHashMap<>(input);changed.put("amount","2");
        assertThat(create(actor,target.tenant(),changed).getResponse().getContentAsString()).contains("50041");
        changed.put("idempotencyKey",UUID.randomUUID().toString());
        assertThat(create(actor,target.tenant(),changed).getResponse().getContentAsString()).contains("50052");
        Map<String,Object> spoof=new LinkedHashMap<>(input);spoof.put("operatorId",target.account().toString());
        var spoofResult=create(actor,target.tenant(),spoof);
        assertThat(spoofResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(spoofResult).get("operatorId").asString()).isEqualTo(actor.account().toString());
        assertThat(tree(spoofResult).get("id").asString()).isEqualTo(fact.get("id").asString());
        var recovered=mvc.perform(get(path(target.tenant())+"/by-key/"+input.get("idempotencyKey")).header("Authorization","Bearer "+actor.token())).andReturn();
        assertThat(tree(recovered)).isEqualTo(fact);
        UUID id=UUID.fromString(fact.get("id").asString());
        assertThat(revoke(actor,target.tenant(),id).getResponse().getContentAsString()).contains("true");
        assertThat(revoke(actor,target.tenant(),id).getResponse().getContentAsString()).contains("false");
        assertThat(tree(mvc.perform(get(path(target.tenant())+"/by-key/"+input.get("idempotencyKey")).header("Authorization","Bearer "+actor.token())).andReturn()).get("status").asString()).isEqualTo("CANCELLED");
        manage(Uuid7.generate(),actor.account(),false,"撤销授权");
        assertThat(context(actor,target.tenant()).getResponse().getStatus()).isEqualTo(403);
    }
    @Test void longBoundariesNumericJsonAndPriceOverflowHaveNoPartialWrites() throws Exception {
        Session s=session(); manage(Uuid7.generate(),s.account(),true,"边界验收");
        String version=tree(context(s,s.tenant())).get("assignmentVersion").asString();
        for (Object amount:List.of("9223372036854775808","0","1.0","-1","01",9007199254740993L)) {
            var request=input(version,"1");request.put("amount",amount);
            var result=create(s,s.tenant(),request);
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id=?",Integer.class,s.tenant())).isZero();
        assertThatThrownBy(() -> packages.createSimulatedPackageOrder(s.tenant(),"DEVICES_MAX",Long.MAX_VALUE,null))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_order WHERE tenant_id=?",Integer.class,s.tenant())).isZero();
        assertThat(create(s,s.tenant(),input(version,Long.toString(Long.MAX_VALUE))).getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(context(s,s.tenant())).get("dimensions").toString()).contains(Long.toString(Long.MAX_VALUE));
    }
    @Test void expiryRecoveryAndTransactionRollbackKeepAuditAndQuotaAtomic() throws Exception {
        Session s=session();manage(Uuid7.generate(),s.account(),true,"事务验收");
        String version=tree(context(s,s.tenant())).get("assignmentVersion").asString();
        var request=input(version,"2");
        TenantContext.set(new TenantScope(s.tenant(),null,s.account()));
        assertThatThrownBy(() -> tx.execute(status -> {
            operations.create(s.tenant(),"DEVICES_MAX","2",(Instant)request.get("startsAt"),(Instant)request.get("endsAt"),"测试",(String)request.get("idempotencyKey"),version);
            throw new IllegalStateException("rollback after audit");
        })).isInstanceOf(IllegalStateException.class);
        TenantContext.clear();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id=? AND action='commercial.adjustment.granted'",Integer.class,s.tenant())).isZero();
        assertThat(tree(context(s,s.tenant())).get("assignmentVersion").asString()).isEqualTo(version);
        UUID id=UUID.fromString(tree(create(s,s.tenant(),request)).get("id").asString());
        jdbc.update("UPDATE sys_tenant_resource_package SET status='EXPIRED' WHERE id=?",id);
        var result=mvc.perform(get(path(s.tenant())+"/by-key/"+request.get("idempotencyKey")).header("Authorization","Bearer "+s.token())).andReturn();
        assertThat(tree(result).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(revoke(s,s.tenant(),id).getResponse().getContentAsString()).contains("50038");
    }
    @Test void databaseAdminIdentityIsAppendOnlyAndAppCannotSelfGrant() throws Exception {
        Session s=session(); UUID operation=Uuid7.generate();
        long version=manage(operation,s.account(),true," 原因 ");
        assertThat(manage(operation,s.account(),true,"原因")).isEqualTo(version);
        assertThatThrownBy(() -> manage(operation,s.account(),false,"原因")).isInstanceOf(SQLException.class);
        try (Connection owner=owner()) {
            try (var query=owner.prepareStatement("SELECT database_actor FROM sys_commercial_operator_operation WHERE operation_id=?")) {
                query.setObject(1,operation);try(var rs=query.executeQuery()){rs.next();assertThat(rs.getString(1)).isEqualTo("commercial_test_admin");}
            }
            assertThatThrownBy(() -> owner.createStatement().executeUpdate("UPDATE sys_commercial_operator_operation SET reason='changed'"))
                    .isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM commercial_operator_manage(?,?,true,'forged')",Uuid7.generate(),s.account())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_commercial_operator SET enabled=true WHERE account_id=?",s.account())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_commercial_operator_operation WHERE account_id=?",s.account())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void managementAuditFailureRollsBackAuthorizationAndOperationReceipt() throws Exception {
        Session s=session(); UUID operation=Uuid7.generate();
        jdbc.update("INSERT INTO sys_audit_log(id,target_type,action) VALUES(?,'test','test.collision')",operation);
        assertThatThrownBy(() -> manage(operation,s.account(),true,"审计必须同事务"))
                .isInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("SELECT commercial_operator_enabled(?,false)",Boolean.class,s.account())).isFalse();
        try(Connection owner=owner();var query=owner.prepareStatement("SELECT count(*) FROM sys_commercial_operator_operation WHERE operation_id=?")) {
            query.setObject(1,operation);
            try(var rows=query.executeQuery()){rows.next();assertThat(rows.getInt(1)).isZero();}
        }
    }
    @Test void admittedTransactionBlocksRevocationAndLaterAdmissionSeesRevocation() throws Exception {
        Session s=session();manage(Uuid7.generate(),s.account(),true,"锁序验收");
        try(Connection app=DriverManager.getConnection(POSTGRES.getJdbcUrl(),APP_ROLE,APP_ROLE_PASSWORD);
            Connection admin=admin()) {
            app.setAutoCommit(false);
            try(var query=app.prepareStatement("SELECT commercial_operator_enabled(?,true)")) {
                query.setObject(1,s.account());try(var rs=query.executeQuery()){rs.next();assertThat(rs.getBoolean(1)).isTrue();}
            }
            int pid;try(var rs=admin.createStatement().executeQuery("SELECT pg_backend_pid()")){rs.next();pid=rs.getInt(1);}
            try(var executor=Executors.newSingleThreadExecutor()) {
                Future<Long> revoked=executor.submit(() -> manage(admin,Uuid7.generate(),s.account(),false,"竞争撤销"));
                try(Connection observer=owner()) {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    boolean blocked=false;
                    while(System.nanoTime()<deadline) {
                        try(var rs=observer.createStatement().executeQuery("SELECT cardinality(pg_blocking_pids("+pid+"))>0")){rs.next();blocked=rs.getBoolean(1);}
                        if(blocked)break;
                        Thread.yield();
                    }
                    assertThat(blocked).isTrue();
                } finally { app.commit(); }
                assertThat(revoked.get(10,TimeUnit.SECONDS)).isGreaterThan(1);
            }
            assertThat(context(s,s.tenant()).getResponse().getStatus()).isEqualTo(403);
        }
    }
    private static String path(UUID tenant){return "/api/v1/operations/tenants/"+tenant+"/adjustments";}
    private MvcResult context(Session s,UUID tenant)throws Exception{return mvc.perform(get(path(tenant)+"/context").header("Authorization","Bearer "+s.token())).andReturn();}
    private MvcResult create(Session s,UUID tenant,Map<String,Object> input)throws Exception{return mvc.perform(post(path(tenant)).header("Authorization","Bearer "+s.token()).contentType("application/json").content(JSON.writeValueAsString(input))).andReturn();}
    private MvcResult revoke(Session s,UUID tenant,UUID id)throws Exception{return mvc.perform(post(path(tenant)+"/"+id+"/revoke").header("Authorization","Bearer "+s.token()).contentType("application/json").content("{\"reason\":\"撤销验收\"}")).andReturn();}
    private static JsonNode tree(MvcResult result){return JSON.readTree(result.getResponse().getContentAsByteArray());}
    private static Map<String,Object> input(String version,String amount){
        var map=new LinkedHashMap<String,Object>(); map.put("dimensionCode","DEVICES_MAX");map.put("amount",amount);
        map.put("startsAt",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS));map.put("endsAt",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        map.put("reason","工单验收");map.put("idempotencyKey",UUID.randomUUID().toString());map.put("expectedAssignmentVersion",version);return map;
    }
    private Session session()throws Exception {
        limiter.clear();String email="commercial-"+UUID.randomUUID()+"@example.com";
        String body="{\"email\":\""+email+"\",\"password\":\"correct-horse-battery-staple\"}";
        var registration=mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body)).andReturn();
        assertThat(registration.getResponse().getStatus()).isIn(200,201,204);
        jdbc.update("UPDATE sys_account SET email_verified_at=now() WHERE email=?",email);
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body)).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        UUID account=jdbc.queryForObject("SELECT id FROM sys_account WHERE email=?",UUID.class,email);
        UUID tenant=jdbc.queryForObject("SELECT tenant_id FROM sys_tenant_member WHERE account_id=?",UUID.class,account);
        var result=new Session(account,tenant,tree(login).get("accessToken").asString());sessions.add(result);return result;
    }
    private static Connection owner()throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    private static Connection admin()throws SQLException {
        try(Connection owner=owner()) {
            owner.createStatement().execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='commercial_test_admin') THEN CREATE ROLE commercial_test_admin LOGIN PASSWORD 'test-only'; END IF; END $$");
            owner.createStatement().execute("GRANT thingslink_commercial_admin TO commercial_test_admin");
        }
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),"commercial_test_admin","test-only");
    }
    private static long manage(UUID op,UUID actor,boolean enabled,String reason)throws SQLException {
        try(Connection admin=admin()){return manage(admin,op,actor,enabled,reason);}
    }
    private static long manage(Connection connection,UUID op,UUID actor,boolean enabled,String reason)throws SQLException {
        try(var query=connection.prepareStatement("SELECT result_version FROM commercial_operator_manage(?,?,?,?)")) {
            query.setObject(1,op);query.setObject(2,actor);query.setBoolean(3,enabled);query.setString(4,reason);
            try(var rs=query.executeQuery()){rs.next();return rs.getLong(1);}
        }
    }
}
