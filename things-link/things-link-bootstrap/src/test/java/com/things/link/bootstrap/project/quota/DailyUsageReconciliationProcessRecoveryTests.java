package com.things.link.bootstrap.project.quota;

import com.things.link.bootstrap.project.quota.fixture.DailyUsageRecoveryProcess;
import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.TrustedProjectUsageFactRecorder;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4-4c: crash a real child JVM after production lease commit; a replacement uses the unchanged
 * scanner, real APP role, PG facts and transactional reconciliation after the natural 120s lease.
 * Isolated DB contains exactly one owned project. Not full-server/HTTP/UTC-midnight qualification.
 */
@Import(DailyUsageReconciliationProcessRecoveryTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DailyUsageReconciliationProcessRecoveryTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("usage_process_recovery").withUsername("thingslink").withPassword("thingslink");
    private static final String URL = startDatabase();
    private static String startDatabase() { DATABASE.start(); return DATABASE.getJdbcUrl(); }
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") ApplicationRunner ignoredSharedQuota;
    @Autowired TrustedProjectUsageFactRecorder facts;
    @Autowired DailyUsageReconciliationService reconciliation;

    @Test @Timeout(240)
    void killedClaimantLeavesFactsForNaturalLeaseTakeoverInReplacementJvm() throws Exception {
        var owner = new JdbcTemplate(new DriverManagerDataSource(URL, DATABASE.getUsername(), DATABASE.getPassword()));
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), policy = UUID.randomUUID();
        Path directory = Files.createDirectories(Path.of("..", "..", "logs", "verification", "r4-usage-process", UUID.randomUUID().toString()).toAbsolutePath());
        var children = new ArrayList<Process>();
        Throwable primary = null;
        try {
            assertThat(owner.queryForObject("SELECT count(*) FROM sys_project", Long.class)).isZero();
            owner.update("INSERT INTO sys_quota_policy(id,code,rest_api_call_daily_limit) VALUES (?,?,2)", policy, "R4P"+policy.toString().replace("-", "").substring(0,20));
            owner.update("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES (?,'Owned usage recovery',?)", tenant, policy);
            owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key,usage_reconcile_after) VALUES (?,?,'Owned usage recovery','sh-1',?,now()-interval '1 minute')", project,tenant,"r4p"+project.toString().replace("-",""));
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (int index=0;index<3;index++) assertThat(facts.recordTrusted(tenant,project,QuotaMetric.REST_API_CALL,
                    "process-rest-"+index,today.atTime(12,index).toInstant(ZoneOffset.UTC))).isTrue();
            var claimant = start(directory.resolve("claimant"), "claim-hold", tenant, project);
            children.add(claimant);
            awaitMarker(claimant,directory.resolve("claimant/claimed"),20);
            Timestamp lease = owner.queryForObject("SELECT usage_reconcile_lease_until FROM sys_project WHERE id=?", Timestamp.class,project);
            assertThat(lease).isNotNull();
            assertThat(owner.queryForObject("SELECT extract(epoch from usage_reconcile_lease_until-clock_timestamp()) FROM sys_project WHERE id=?", Double.class, project)).isBetween(110d,120d);
            assertThat(count(owner,"sys_usage_counter_daily",project)).isZero();
            claimant.destroyForcibly();
            assertThat(claimant.waitFor(10,TimeUnit.SECONDS)).as("exact owned claimant is reaped").isTrue();
            assertThat(count(owner,"sys_usage_fact",project)).isEqualTo(3);
            var replacement = start(directory.resolve("replacement"),"scan",tenant,project);
            children.add(replacement);
            assertThat(replacement.pid()).isNotEqualTo(claimant.pid());
            awaitMarker(replacement,directory.resolve("replacement/ready"),20);
            // No SQL shortens the lease; the replacement must leave the original claim intact.
            assertThat(owner.queryForObject("SELECT usage_reconcile_lease_until FROM sys_project WHERE id=?", Timestamp.class,project)).isEqualTo(lease);
            assertThat(Files.exists(directory.resolve("replacement/claimed"))).isFalse();
            awaitMarker(replacement,directory.resolve("replacement/completed"),150);
            assertThat(replacement.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(replacement.exitValue()).isZero();
            assertThat(owner.queryForObject("SELECT updated_at>=? AND usage_reconcile_lease_until IS NULL AND usage_reconcile_after>updated_at FROM sys_project WHERE id=?",Boolean.class,lease,project)).isTrue();
            assertThat(owner.queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='REST_API_CALL' AND usage_date=?",Long.class,project,today)).isEqualTo(3);
            assertThat(count(owner,"sys_usage_fact",project)).isEqualTo(3);
            assertThat(facts.recordTrusted(tenant,project,QuotaMetric.REST_API_CALL,"process-rest-0",today.atTime(12,0).toInstant(ZoneOffset.UTC))).isTrue();
            reconciliation.reconcile(new DailyUsageScope(tenant,project));
            assertThat(count(owner,"sys_usage_counter_daily",project)).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=?",Long.class,project)).isEqualTo(3);
            assertThat(count(owner,"sys_usage_fact",project)).isEqualTo(3);
            assertThat(LocalDate.now(ZoneOffset.UTC)).as("ordinary-day proof; natural UTC rollover requires its own run").isEqualTo(today);
            Files.writeString(directory.resolve("result.json"),"{\"result\":\"PASS\",\"processes\":2,\"productionLeaseSeconds\":120,\"facts\":3,\"projection\":3,\"http\":false,\"naturalUtcRollover\":false}\n");
        } catch (Exception | AssertionError failure) {
            primary = failure;
            throw failure;
        } finally {
            List<Exception> failures = new ArrayList<>();
            for(var child:children)try{if(child.isAlive())child.destroyForcibly();if(!child.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("Owned usage child was not reaped");}catch(Exception e){failures.add(e);}
            // Each owned cleanup is attempted separately. No shared project, Redis, or container is stopped.
            for(String sql:List.of("DELETE FROM sys_usage_fact WHERE project_id=?","DELETE FROM sys_usage_counter_daily WHERE project_id=?","DELETE FROM sys_project WHERE id=?"))try{owner.update(sql,project);}catch(Exception e){failures.add(e);}
            try{owner.update("DELETE FROM sys_tenant WHERE id=?",tenant);}catch(Exception e){failures.add(e);}
            try{owner.update("DELETE FROM sys_quota_policy WHERE id=?",policy);}catch(Exception e){failures.add(e);}
            if(!failures.isEmpty()){var cleanup=new IllegalStateException("Owned usage recovery cleanup incomplete");failures.forEach(cleanup::addSuppressed);if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private long count(JdbcTemplate jdbc,String table,UUID project){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Long.class,project);}
    private Process start(Path directory,String mode,UUID tenant,UUID project)throws Exception{
        Files.createDirectories(directory);
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx192m","-cp",classpath,DailyUsageRecoveryProcess.class.getName());
        var settings = new java.util.Properties();
        settings.putAll(java.util.Map.of("R4_USAGE_DIRECTORY",directory.toString(),"R4_USAGE_MODE",mode,"R4_USAGE_TENANT",tenant.toString(),"R4_USAGE_PROJECT",project.toString(),"R4_USAGE_URL",URL,"R4_USAGE_USER",APP_ROLE,"R4_USAGE_PASSWORD",APP_ROLE_PASSWORD));
        Path log=directory.resolve("process.log");
        Files.createFile(log);
        if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(log,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        Process child=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try(var input=child.getOutputStream()) { settings.store(input,null); }
        catch(Exception failure){child.destroyForcibly();if(!child.waitFor(10,TimeUnit.SECONDS))failure.addSuppressed(new IllegalStateException("Owned child not reaped after private configuration failure"));throw failure;}
        return child;
    }
    private void awaitMarker(Process child,Path marker,int seconds){
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofMillis(100)).until(()->{
            if(Files.exists(marker))return true;
            assertThat(child.isAlive()).as("Owned child remains alive until marker; inspect private process.log").isTrue();return false;
        });
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration{
        @Bean DynamicPropertyRegistrar ownedUsageDatabase(){return registry->{
            registry.add("spring.datasource.url",()->URL);registry.add("spring.flyway.url",()->URL);
            registry.add("spring.flyway.user",DATABASE::getUsername);registry.add("spring.flyway.password",DATABASE::getPassword);
            registry.add("things-link.quota.daily-reconciliation.enabled",()->"false");
            registry.add("things-link.outbox.publisher.enabled",()->"false");registry.add("spring.kafka.listener.auto-startup",()->"false");registry.add("things-link.notification.retry.enabled",()->"false");
        };}
    }
}
