package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.AutomationQuotaService;
import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.infrastructure.operations.AutomationQuotaCli;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 真PG普通应用/受控运营两个身份；并发与未知提交不能用Mockito模拟数据库结论。 */
class AutomationQuotaIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired AutomationQuotaService service;
    @Autowired DailyUsageReconciliationService reconciliation;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.things.link.project.infrastructure.persistence.JdbcAutomationQuotaRepository quotaRepository;
    UUID tenant, project, otherProject, policy;
    String operator;
    static final String PASSWORD = "quota-test-only-password";

    @BeforeEach void seed() throws Exception {
        tenant=Uuid7.generate(); project=Uuid7.generate(); otherProject=Uuid7.generate(); policy=Uuid7.generate();
        operator="quota_test_"+UUID.randomUUID().toString().replace("-", "");
        try (var owner=owner(); var statement=owner.createStatement()) {
            statement.execute("CREATE ROLE "+operator+" LOGIN PASSWORD '"+PASSWORD+"'");
            statement.execute("GRANT thingslink_quota_operator TO "+operator);
        }
        jdbc.update("INSERT INTO sys_quota_policy(id,code) VALUES (?,?)",policy,"AQ"+policy.toString().substring(9));
        jdbc.update("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES (?,?,?)",tenant,"quota-test",policy);
        for(UUID id:List.of(project,otherProject)) jdbc.update("""
                INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'quota-test','sh-1',?)
                """,id,tenant,"aq"+id.toString().replace("-", ""));
    }
    @AfterEach void cleanup() throws Exception {
        TenantContext.clear();
        try(var owner=owner();var q=owner.createStatement()) {
            q.executeUpdate("DELETE FROM sys_automation_quota_reservation WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_usage_counter_daily WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_project WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_tenant WHERE id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_quota_policy WHERE id='"+policy+"'");
            q.execute("DROP ROLE "+operator);
        }
        // 不可变操作历史/平台审计保留到隔离测试容器回收，不关闭触发器删证据。
    }
    @Test void defaultIsDisabledAndAppCannotOperateOrBypassGuard() {
        assertThat(reserve(project,Uuid7.generate())).isEqualTo(AutomationQuotaService.Reservation.NOT_ENABLED);
        assertThatThrownBy(()->jdbc.update("UPDATE sys_quota_policy SET automation_execution_daily_limit=10 WHERE id=?",policy))
                .rootCause().hasMessageContaining("quota operator required");
        assertThatThrownBy(()->jdbc.queryForList("SELECT * FROM automation_quota_set(?,?,1,10)",Uuid7.generate(),policy))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(()->service.reserve(tenant,project,Uuid7.generate()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void noncommercialTechnicalLimitUsesTrustedDatabaseRowAndRetainsSharedHardLimit() throws Exception {
        try (var owner=owner(); var update=owner.prepareStatement("""
                UPDATE sys_deployment_automation_entitlement
                   SET entitlement_mode='NONCOMMERCIAL',automation_execution_daily_limit=2
                 WHERE singleton
                """)) {
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        try {
            boolean enabled = inProject(project,()->tx.execute(status->{
                rls.establish(tenant,project);
                return service.enabled(tenant,project);
            }));
            assertThat(enabled).isTrue();
            UUID first=Uuid7.generate();
            assertThat(reserve(project,first)).isEqualTo(AutomationQuotaService.Reservation.RESERVED);
            assertThat(reserve(project,first)).isEqualTo(AutomationQuotaService.Reservation.ALREADY_RESERVED);
            assertThat(reserve(otherProject,Uuid7.generate())).isEqualTo(AutomationQuotaService.Reservation.RESERVED);
            assertThat(reserve(project,Uuid7.generate())).isEqualTo(AutomationQuotaService.Reservation.QUOTA_EXCEEDED);
            assertThat(counter(project)+counter(otherProject)).isEqualTo(2);
            assertThatThrownBy(()->jdbc.queryForObject(
                    "SELECT automation_execution_daily_limit FROM sys_deployment_automation_entitlement",
                    Long.class)).rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(()->jdbc.update("""
                    UPDATE sys_deployment_automation_entitlement
                       SET automation_execution_daily_limit=100 WHERE singleton
                    """)).rootCause().hasMessageContaining("permission denied");
            inProject(otherProject,()->tx.execute(status->{
                rls.establish(tenant,otherProject);
                assertThat(service.enabled(tenant,project)).isFalse();
                assertThat(service.reserve(tenant,project,Uuid7.generate()))
                        .isEqualTo(AutomationQuotaService.Reservation.SCOPE_REJECTED);
                return null;
            }));
        } finally {
            try (var owner=owner(); var update=owner.prepareStatement("""
                    UPDATE sys_deployment_automation_entitlement
                       SET entitlement_mode='COMMERCIAL',automation_execution_daily_limit=NULL
                     WHERE singleton
                    """)) {
                update.executeUpdate();
            }
        }
    }
    @Test void missingDeploymentEntitlementRowFailsClosed() throws Exception {
        try (var owner=owner(); var delete=owner.prepareStatement(
                "DELETE FROM sys_deployment_automation_entitlement WHERE singleton")) {
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
        try {
            boolean enabled = inProject(project,()->tx.execute(status->{
                rls.establish(tenant,project);
                return service.enabled(tenant,project);
            }));
            assertThat(enabled).isFalse();
            assertThatThrownBy(() -> reserve(project,Uuid7.generate()))
                    .rootCause().hasMessageContaining("not provisioned");
            assertThat(counter(project)).isZero();
        } finally {
            try (var owner=owner(); var insert=owner.prepareStatement("""
                    INSERT INTO sys_deployment_automation_entitlement
                        (singleton,entitlement_mode,automation_execution_daily_limit)
                    VALUES (true,'COMMERCIAL',NULL)
                    """)) {
                insert.executeUpdate();
            }
        }
    }
    @Test void operatorCasIsIdempotentAuditedAndDoesNotAllowDirectWrites() throws Exception {
        UUID op=Uuid7.generate();
        assertThat(set(op,1,2)).isEqualTo(2);
        assertThat(set(op,1,2)).isEqualTo(2);
        assertThatThrownBy(()->set(op,1,3)).isInstanceOf(SQLException.class).hasMessageContaining("operation conflict");
        assertThatThrownBy(()->set(Uuid7.generate(),1,3)).isInstanceOf(SQLException.class).hasMessageContaining("version conflict");
        try(var c=operator();var q=c.createStatement()) {
            assertThatThrownBy(()->q.executeUpdate("UPDATE sys_quota_policy SET automation_execution_daily_limit=99 WHERE id='"+policy+"'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
        try(var c=owner();var q=c.createStatement()) {
            try(var r=q.executeQuery("SELECT database_actor FROM sys_automation_quota_operation WHERE operation_id='"+op+"'")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo(operator);
            }
            try(var r=q.executeQuery("SELECT count(*) FROM sys_audit_log WHERE id='"+op+"' AND actor_account_id IS NULL")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(()->q.executeUpdate("DELETE FROM sys_automation_quota_operation WHERE operation_id='"+op+"'"))
                    .hasMessageContaining("immutable");
        }
    }
    @Test void reservationAndCounterRollBackAndRetryOnlyChargesOnce() throws Exception {
        set(Uuid7.generate(),1,2); UUID execution=Uuid7.generate();
        inProject(project,()->tx.execute(status->{
            rls.establish(tenant,project);
            assertThat(service.reserve(tenant,project,execution)).isEqualTo(AutomationQuotaService.Reservation.RESERVED);
            status.setRollbackOnly(); return null;
        }));
        assertThat(reserve(project,execution)).isEqualTo(AutomationQuotaService.Reservation.RESERVED);
        assertThat(reserve(project,execution)).isEqualTo(AutomationQuotaService.Reservation.ALREADY_RESERVED);
        assertThat(reserve(otherProject,execution)).isEqualTo(AutomationQuotaService.Reservation.SCOPE_REJECTED);
        assertThat(counter(project)).isEqualTo(1);
        inProject(project,()->{reconciliation.reconcile(new DailyUsageScope(tenant,project)); return null;});
        assertThat(counter(project)).isEqualTo(1);
    }
    @Test void tenantSharedLastSlotCannotBeSpentByTwoProjects() throws Exception {
        set(Uuid7.generate(),1,1);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<AutomationQuotaService.Reservation>> tasks=new ArrayList<>();
            for(UUID id:List.of(project,otherProject)) tasks.add(pool.submit(()->{
                ready.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("barrier");
                return reserve(id,Uuid7.generate());
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(AutomationQuotaService.Reservation.RESERVED,
                    AutomationQuotaService.Reservation.QUOTA_EXCEEDED);
            assertThat(counter(project)+counter(otherProject)).isEqualTo(1);
        }
    }
    @Test void scopeAndRlsRejectForeignReservationsAndDirectMutation() throws Exception {
        set(Uuid7.generate(),1,2); UUID execution=Uuid7.generate();reserve(project,execution);
        inProject(otherProject,()-> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_automation_quota_reservation",Long.class)).isZero();
            assertThatThrownBy(()->jdbc.update("DELETE FROM sys_automation_quota_reservation"))
                    .rootCause().hasMessageContaining("permission denied");
            return tx.execute(status->{
                rls.establish(tenant,otherProject);
                assertThat(service.reserve(tenant,project,Uuid7.generate())).isEqualTo(AutomationQuotaService.Reservation.SCOPE_REJECTED);
                return null;
            });
        });
    }
    @Test void disablingBlocksNewReservationsButKeepsOldIdentity() throws Exception {
        set(Uuid7.generate(),1,2);UUID execution=Uuid7.generate();reserve(project,execution);
        set(Uuid7.generate(),2,0);
        assertThat(reserve(project,execution)).isEqualTo(AutomationQuotaService.Reservation.ALREADY_RESERVED);
        assertThat(reserve(project,Uuid7.generate())).isEqualTo(AutomationQuotaService.Reservation.NOT_ENABLED);
        inProject(project,()->{
            var rows=jdbc.queryForList("SELECT * FROM project_quota_overview((now() AT TIME ZONE 'UTC')::date) WHERE metric='AUTOMATION_EXECUTION'");
            assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("limit_value")).isEqualTo(0L);
            assertThat(rows.getFirst().get("project_used_value")).isEqualTo(1L);return null;
        });
    }
    @Test void cliCommitUnknownIsRecoveredBySameOperationWithoutSecondAudit() throws Exception {
        UUID op=Uuid7.generate(); AtomicInteger invalidations=new AtomicInteger();
        String[] args={"set",op.toString(),policy.toString(),"1","3"};
        var out=new ByteArrayOutputStream();
        int exit=AutomationQuotaCli.run(args,environment(),new PrintStream(out),env->{
            Connection actual=operator();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,values)->{
                if(method.getName().equals("commit")){actual.commit();throw new SQLException("secret address confirmation lost");}
                try{return method.invoke(actual,values);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
        },(env,id,version)->{invalidations.incrementAndGet();return true;});
        assertThat(exit).isEqualTo(75); assertThat(out.toString()).contains("UNKNOWN",op.toString()).doesNotContain("secret address",PASSWORD);
        assertThat(invalidations.get()).isZero(); out.reset();
        assertThat(AutomationQuotaCli.run(new String[]{"status",op.toString()},environment(),new PrintStream(out),
                env->operator(),(env,id,version)->{invalidations.incrementAndGet();return true;})).isZero();
        assertThat(out.toString()).contains("COMMITTED","PUBLISHED","\"resultVersion\":2");
        assertThat(invalidations.get()).isEqualTo(1);assertThat(set(op,1,3)).isEqualTo(2);
    }
    @Test void cliInspectIsReadOnlyAndBadArgumentsNeverConnect() {
        AtomicInteger connections=new AtomicInteger();var out=new ByteArrayOutputStream();
        assertThat(AutomationQuotaCli.run(new String[]{"set",Uuid7.generate().toString(),policy.toString(),"1","-1"},
                environment(),new PrintStream(out),env->{connections.incrementAndGet();return operator();},(a,b,c)->true)).isEqualTo(2);
        assertThat(connections.get()).isZero();out.reset();
        assertThat(AutomationQuotaCli.run(new String[]{"inspect",policy.toString()},environment(),new PrintStream(out),
                env->operator(),(a,b,c)->{throw new AssertionError("inspect must not invalidate");})).isZero();
        assertThat(out.toString()).contains("INSPECTED","\"policyVersion\":1","\"affectedTenants\":1");
    }
    @Test void staleSnapshotIsolationCannotBypassSharedQuotaLock() throws Exception {
        set(Uuid7.generate(),1,1);
        var repeatable=new TransactionTemplate(tx.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->inProject(project,()->repeatable.execute(status->{
            rls.establish(tenant,project);return service.reserve(tenant,project,Uuid7.generate());
        }))).rootCause().hasMessageContaining("READ COMMITTED");
        assertThat(counter(project)).isZero();
    }

    @Test void reconciliationHoldsProjectLockBeforeCountingOrUpdatingUsage() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),proceed=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{
            DailyUsageScope scope=call.getArgument(0);
            if(scope.projectId().equals(project)) {
                entered.countDown();
                if(!proceed.await(10,TimeUnit.SECONDS)) throw new AssertionError("release barrier");
            }
            return call.callRealMethod();
        }).when(quotaRepository).calculate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<?> worker=pool.submit(()->inProject(project,()->{
                reconciliation.reconcile(new DailyUsageScope(tenant,project));return null;
            }));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try(var c=owner();var query=c.prepareStatement("SELECT id FROM sys_project WHERE id=? FOR UPDATE NOWAIT")) {
                    c.setAutoCommit(false);query.setObject(1,project);
                    assertThatThrownBy(query::executeQuery).isInstanceOf(SQLException.class)
                            .extracting("SQLState").isEqualTo("55P03");
                    c.rollback();
                }
            } finally {proceed.countDown();worker.get(10,TimeUnit.SECONDS);}
        }
    }

    @Test void actualOfflineProcessPublishesExistingCacheProtocolAfterCommit() throws Exception {
        UUID operation=Uuid7.generate();
        var received=new java.util.concurrent.LinkedBlockingQueue<String>();
        CountDownLatch subscribed=new CountDownLatch(1);
        class Listener implements org.springframework.data.redis.connection.MessageListener,
                org.springframework.data.redis.connection.SubscriptionListener {
            @Override public void onMessage(org.springframework.data.redis.connection.Message message,byte[] pattern) {
                String text=new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8);
                if(text.contains(policy.toString())) received.add(text);
            }
            @Override public void onChannelSubscribed(byte[] channel,long count){subscribed.countDown();}
        }
        var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();factory.start();
        var listener=new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.addMessageListener(new Listener(),new org.springframework.data.redis.listener.ChannelTopic(
                com.things.link.support.cache.CacheInvalidationPublisher.CHANNEL));
        listener.afterPropertiesSet();listener.start();
        Process process=null;
        try {
            assertThat(subscribed.await(5,TimeUnit.SECONDS)).isTrue();
            var builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    AutomationQuotaCli.class.getName(),"set",operation.toString(),policy.toString(),"1","5");
            builder.environment().putAll(environment());
            builder.environment().put("TC_QUOTA_OPERATOR_SSLMODE","disable");
            builder.environment().put("TC_QUOTA_REDIS_HOST",REDIS.getHost());
            builder.environment().put("TC_QUOTA_REDIS_PORT",REDIS.getMappedPort(6379).toString());
            // JVM启动提示属于stderr，不能混进CLI的一行JSON stdout协议；每轮显式制造该反例。
            builder.environment().merge("JAVA_TOOL_OPTIONS", "-Dthingslink.test.cli.stdout-separation=true",
                    (existing, probe) -> existing + " " + probe);
            builder.redirectErrorStream(false);process=builder.start();
            assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String diagnostics=new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output.lines().toList()).hasSize(1);
            assertThat(output).contains("COMMITTED","PUBLISHED").doesNotContain(PASSWORD);
            assertThat(diagnostics).contains("Picked up JAVA_TOOL_OPTIONS").doesNotContain(PASSWORD);
            String event=received.poll(5,TimeUnit.SECONDS);
            assertThat(event).contains("QUOTA_POLICY","UPDATE",policy.toString(),"\"version\":2");
        } finally {
            if(process!=null && process.isAlive()) process.destroyForcibly();
            listener.destroy();factory.destroy();
        }
    }

    private AutomationQuotaService.Reservation reserve(UUID id,UUID execution) {
        return inProject(id,()->tx.execute(status->{rls.establish(tenant,id);return service.reserve(tenant,id,execution);}));
    }
    private long counter(UUID id) {
        return inProject(id,()->jdbc.queryForObject("SELECT COALESCE(sum(used_value),0) FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'",Long.class,id));
    }
    private <T>T inProject(UUID id,java.util.function.Supplier<T> work) {
        TenantContext.set(new TenantScope(tenant,id,Uuid7.generate()));
        try{return work.get();}finally{TenantContext.clear();}
    }
    private long set(UUID operation,long expected,long limit) throws SQLException {
        try(var c=operator();var q=c.prepareStatement("SELECT result_version FROM automation_quota_set(?,?,?,?)")) {
            q.setObject(1,operation);q.setObject(2,policy);q.setLong(3,expected);q.setLong(4,limit);
            try(var r=q.executeQuery()){assertThat(r.next()).isTrue();return r.getLong(1);}
        }
    }
    private Map<String,String> environment(){return Map.of("TC_QUOTA_OPERATOR_JDBC_URL",POSTGRES.getJdbcUrl().split("\\?")[0],
            "TC_QUOTA_OPERATOR_USER",operator,"TC_QUOTA_OPERATOR_PASSWORD",PASSWORD);}
    private Connection operator() throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),operator,PASSWORD);}
    private Connection owner() throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
}
