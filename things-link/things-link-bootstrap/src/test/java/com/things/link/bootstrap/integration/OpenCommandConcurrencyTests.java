package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 使用独立真实事务及pg_stat_activity锁屏障，不靠固定sleep猜竞态已发生。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="things-link.integration.api-key.enabled=true")
class OpenCommandConcurrencyTests extends OpenCommandHttpFixture {
    void waiting(AtomicInteger pid){await().atMost(Duration.ofSeconds(10)).until(()->pid.get()>0&&Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,pid.get())));}
    static void latch(CountDownLatch value){try{if(!value.await(15,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    @Test void concurrentSameKeyCreatesOneCommandAndReceipt()throws Exception{
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);List<Future<UUID>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(executor.submit(()->{latch(start);return service.submit(principal,device,"race","start",json.readTree("{}")).commandId();}));
            start.countDown();Set<UUID> ids=new HashSet<>();for(var f:futures)ids.add(f.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);assertThat(count("integ_command_receipt")).isEqualTo(1);assertThat(count("ts_device_command")).isEqualTo(1);assertThat(count("sys_outbox_event")).isEqualTo(1);
        }
    }
    @Test void revocationCommittedBeforeWaitingSubmissionRejectsIt()throws Exception{
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var written=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
            var revoke=executor.submit(()->tx.execute(s->{keys.revoke(tenant,project,account,Uuid7.generate(),principal.keyId());written.countDown();latch(release);return null;}));
            latch(written);
            var pending=executor.submit(()->{try{return tx.execute(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));service.submit(principal,device,"after-revoke","start",json.readTree("{}"));return (RuntimeException)null;});}catch(RuntimeException e){return e;}});
            try{waiting(pid);}finally{release.countDown();}
            revoke.get(15,TimeUnit.SECONDS);assertThat(pending.get(15,TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
            assertThat(count("ts_device_command")).isZero();
        }
    }
    @Test void admittedSubmissionCommitsBeforeWaitingRevocation()throws Exception{
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var written=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
            var submit=executor.submit(()->tx.execute(s->{var result=service.submit(principal,device,"before-revoke","start",json.readTree("{}"));written.countDown();latch(release);return result.commandId();}));
            latch(written);
            var revoke=executor.submit(()->tx.execute(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));keys.revoke(tenant,project,account,Uuid7.generate(),principal.keyId());return null;}));
            try{waiting(pid);}finally{release.countDown();}
            assertThat(submit.get(15,TimeUnit.SECONDS)).isNotNull();revoke.get(15,TimeUnit.SECONDS);
            assertThat(count("ts_device_command")).isEqualTo(1);assertThat(count("integ_command_receipt")).isEqualTo(1);
            assertThatThrownBy(()->authentication.authenticate(secret,"127.0.0.1")).isInstanceOf(BusinessException.class);
        }
    }
    @Test void keyExpiringDuringProjectLockWaitCannotBeAccepted()throws Exception{
        Instant expiry=owner.queryForObject("SELECT clock_timestamp()+interval '3 seconds'",Timestamp.class).toInstant();
        String shortKey=keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("short",List.of("device:control"),List.of("127.0.0.1/32"),expiry)).secret();
        var shortPrincipal=authentication.authenticate(shortKey,"127.0.0.1");var pid=new AtomicInteger();
        try(var lock=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            lock.setAutoCommit(false);try(var st=lock.prepareStatement("SELECT id FROM sys_project WHERE id=? FOR UPDATE")){st.setObject(1,project);st.executeQuery().close();}
            var pending=executor.submit(()->{try{return tx.execute(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));service.submit(shortPrincipal,device,"expired","start",json.readTree("{}"));return (RuntimeException)null;});}catch(RuntimeException e){return e;}});
            try{waiting(pid);await().atMost(Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT clock_timestamp()>=?",Boolean.class,Timestamp.from(expiry)));}finally{lock.commit();}
            assertThat(pending.get(15,TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);assertThat(count("ts_device_command")).isZero();
        }
    }
    @Test void accountDisabledWhileWaitingCannotBeAccepted()throws Exception{
        var pid=new AtomicInteger();
        try(var lock=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            lock.setAutoCommit(false);try(var st=lock.prepareStatement("UPDATE sys_account SET status='DISABLED' WHERE id=?")){st.setObject(1,account);st.executeUpdate();}
            var pending=executor.submit(()->{try{return tx.execute(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));service.submit(principal,device,"disabled","start",json.readTree("{}"));return (RuntimeException)null;});}catch(RuntimeException e){return e;}});
            try{waiting(pid);}finally{lock.commit();}
            assertThat(pending.get(15,TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);assertThat(count("ts_device_command")).isZero();
        }
    }
}
