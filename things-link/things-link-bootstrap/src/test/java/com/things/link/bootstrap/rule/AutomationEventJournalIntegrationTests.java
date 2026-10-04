package com.things.link.bootstrap.rule;

import com.things.link.rule.application.automation.AutomationEventJournal;
import com.things.link.rule.domain.AutomationEventReceiptRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** 真PG事件幂等、权限及回滚；不以这些存储测试冒充Kafka offset验收。 */
class AutomationEventJournalIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TransactionLocalRlsScope rls;
    @Autowired AutomationEventJournal journal;
    @Autowired AutomationEventReceiptRepository repository;
    UUID tenant,project,other,device;
    int partition;
    @BeforeEach void seed() throws Exception {
        tenant=Uuid7.generate();project=Uuid7.generate();other=Uuid7.generate();device=Uuid7.generate();
        partition=ThreadLocalRandom.current().nextInt(10000,1000000000);
        try(var c=owner();var q=c.createStatement()) {
            q.executeUpdate("INSERT INTO sys_tenant(id,name) VALUES ('"+tenant+"','event-test')");
            for(var id:List.of(project,other))q.executeUpdate("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES ('"+id+"','"+tenant+"','event-test','sh-1','"+id+"')");
            q.executeUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name) VALUES ('"+device+"','"+tenant+"','"+project+"','event-device','event-test')");
        }
    }
    @AfterEach void cleanup() throws Exception {
        try(var c=owner();var q=c.createStatement()) {
            q.executeUpdate("DELETE FROM rule_automation_ingress_rejection WHERE partition_id="+partition);
            q.executeUpdate("DELETE FROM rule_automation_event_receipt WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM dev_device WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_project WHERE tenant_id='"+tenant+"'");
            q.executeUpdate("DELETE FROM sys_tenant WHERE id='"+tenant+"'");
        }
    }
    /** 全项目重置只清无执行空计划，非空冻结计划仍必须由拥有者清理。 */
    @Test void explicitProjectFixtureResetRemovesOnlyUnreferencedEmptyPlans() {
        var empty=event(Instant.now(),Map.of("temperature",1));
        var planned=event(Instant.now(),Map.of("temperature",2));
        freeze(empty,1,()->AutomationEventJournal.Decision.accept(List.of()));
        freeze(planned,2,()->AutomationEventJournal.Decision.accept(List.of(Uuid7.generate())));
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        tx.executeWithoutResult(status->{
            rls.establish(tenant,project);
            assertThat(repository.find(project,empty.sourceEventId())).isEmpty();
            assertThat(repository.find(project,planned.sourceEventId())).isPresent();
        });
    }
    @Test void emptyPlanReplayNeverRequeriesActiveDirectory() {
        var event=event(Instant.now(),Map.of("temperature",1));var count=new AtomicInteger();
        var first=freeze(event,1,()->{count.incrementAndGet();return AutomationEventJournal.Decision.accept(List.of());});
        var again=freeze(event,2,()->{throw new AssertionError("重投不得读取新目录");});
        assertThat(again.receipt()).isEqualTo(first.receipt());assertThat(count).hasValue(1);
        assertThat(first.receipt().plan()).isEmpty();
    }
    @Test void conflictingContentRecordsSeparateRejectionWithoutChangingOriginalPlan() throws Exception {
        var event=event(Instant.now(),Map.of("temperature",1));UUID version=Uuid7.generate();
        var first=freeze(event,1,()->AutomationEventJournal.Decision.accept(List.of(version)));
        var conflict=new AutomationPropertyAccepted(1,event.sourceEventId(),tenant,project,device,"1.0.0",event.occurredAt(),event.acceptedAt(),Map.of("temperature",2),"test");
        assertThat(freeze(conflict,2,()->{throw new AssertionError();}).conflict()).isTrue();
        assertThat(freeze(conflict,2,()->{throw new AssertionError();}).conflict()).isTrue();
        assertThat(freeze(event,3,()->{throw new AssertionError();}).receipt()).isEqualTo(first.receipt());
        try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT count(*),max(reason_code) FROM rule_automation_ingress_rejection WHERE partition_id="+partition)) {
            r.next();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getString(2)).isEqualTo("EVENT_CONFLICT");
        }
    }
    @Test void permanentAgeFutureAndInputRefusalsHaveDurableResults() {
        for(var item:List.of(Map.entry(event(Instant.now().minusSeconds(8*86400),Map.of("x",1)),"EVENT_EXPIRED"),
                Map.entry(event(Instant.now().plusSeconds(120),Map.of("x",1)),"EVENT_TIME_INVALID"),
                Map.entry(event(Instant.now(),Map.of("x","x".repeat(17000))),"INPUT_LIMIT"))) {
            var result=freeze(item.getKey(),1,()->{throw new AssertionError("拒绝后不得读取目录");}).receipt();
            assertThat(result.reasonCode()).isEqualTo(item.getValue());assertThat(result.plan()).isEmpty();
            assertThat(freeze(item.getKey(),2,()->{throw new AssertionError();}).receipt().id()).isEqualTo(result.id());
        }
    }
    @Test void outerRollbackDoesNotKeepReceiptOrSecurityFact() throws Exception {
        var event=event(Instant.now(),Map.of("x",1));
        tx.executeWithoutResult(s->{rls.establish(tenant,project);journal.freeze(event,transport(1),()->AutomationEventJournal.Decision.accept(List.of()));
            repository.rejectTransport(Uuid7.generate(),partition,2,"0".repeat(64),"ENVELOPE_INVALID");s.setRollbackOnly();});
        tx.executeWithoutResult(s->{rls.establish(tenant,project);assertThat(repository.find(project,event.sourceEventId())).isEmpty();});
        try(var c=owner();var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM rule_automation_ingress_rejection WHERE partition_id="+partition)) {r.next();assertThat(r.getInt(1)).isZero();}
    }
    @Test void securityRowsAreNotReadableOrMutableByApplicationAndPositionCannotBeReused() {
        UUID id=tx.execute(s->repository.rejectTransport(Uuid7.generate(),partition,1,"0".repeat(64),"KEY_MISMATCH"));
        UUID replay=tx.execute(s->repository.rejectTransport(Uuid7.generate(),partition,1,"0".repeat(64),"KEY_MISMATCH"));
        assertThat(replay).isEqualTo(id);
        assertThatThrownBy(()->tx.execute(s->repository.rejectTransport(Uuid7.generate(),partition,1,"1".repeat(64),"KEY_MISMATCH")))
                .rootCause().hasMessageContaining("position conflict");
        assertThatThrownBy(()->jdbc.queryForList("SELECT * FROM rule_automation_ingress_rejection")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(()->jdbc.update("DELETE FROM rule_automation_ingress_rejection")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(()->repository.rejectTransport(Uuid7.generate(),partition,2,"0".repeat(64),"KEY_MISMATCH"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void receiptRlsAndDatabaseConstraintsRejectOverwriteAndForeignDevice() {
        var event=event(Instant.now(),Map.of("x",1));freeze(event,1,()->AutomationEventJournal.Decision.reject("PROJECT_READ_ONLY"));
        tx.executeWithoutResult(s->{rls.establish(tenant,other);assertThat(repository.find(project,event.sourceEventId())).isEmpty();});
        assertThatThrownBy(()->tx.execute(s->{rls.establish(tenant,project);return jdbc.update("UPDATE rule_automation_event_receipt SET plan='[]' WHERE project_id=?",project);}))
                .rootCause().hasMessageContaining("permission denied");
        var bad=new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,other,device,"1.0.0",Instant.now(),Instant.now(),Map.of("x",1),"test");
        assertThatThrownBy(()->tx.execute(s->{rls.establish(tenant,other);return journal.freeze(bad,transport(2),()->AutomationEventJournal.Decision.accept(List.of()));}))
                .rootCause().hasMessageContaining("foreign key");
    }
    @Test void retentionUsesThirtyUtcDaysAcrossSessionDaylightSavingBoundary() {
        var accepted=Instant.parse("2026-10-20T12:00:00Z");
        tx.executeWithoutResult(status->{
            rls.establish(tenant,project);jdbc.execute("SET LOCAL TIME ZONE 'America/Los_Angeles'");
            var receipt=new com.things.link.rule.domain.AutomationEventReceipt(Uuid7.generate(),tenant,project,Uuid7.generate(),device,
                    accepted,accepted,"0".repeat(64),List.of(),com.things.link.rule.domain.AutomationEventReceipt.Result.ACCEPTED,
                    null,accepted.plus(java.time.Duration.ofDays(30)));
            repository.insert(receipt);
            assertThat(repository.find(project,receipt.sourceEventId()).orElseThrow().expiresAt()).isEqualTo(receipt.expiresAt());
        });
    }
    @Test void concurrentSameEventCallsPlanOnce() throws Exception {
        var event=event(Instant.now(),Map.of("x",1));var count=new AtomicInteger();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<UUID>>();
            for(int i=0;i<2;i++)jobs.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return freeze(event,1,()->{
                count.incrementAndGet();return AutomationEventJournal.Decision.accept(List.of());}).receipt().id();}));
            assertThat(jobs.get(0).get(15,TimeUnit.SECONDS)).isEqualTo(jobs.get(1).get(15,TimeUnit.SECONDS));
        }
        assertThat(count).hasValue(1);
    }
    private AutomationEventJournal.Outcome freeze(AutomationPropertyAccepted event,long offset,Supplier<AutomationEventJournal.Decision> plan) {
        return tx.execute(s->{rls.establish(tenant,project);return journal.freeze(event,transport(offset),plan);});
    }
    private AutomationPropertyAccepted event(Instant accepted,Map<String,Object> payload) {
        accepted=accepted.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new AutomationPropertyAccepted(1,Uuid7.generate(),tenant,project,device,"1.0.0",accepted,accepted,payload,"test");
    }
    private AutomationEventJournal.Transport transport(long offset) {return new AutomationEventJournal.Transport(partition,offset,"0".repeat(64));}
    private Connection owner() throws SQLException {return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
}
