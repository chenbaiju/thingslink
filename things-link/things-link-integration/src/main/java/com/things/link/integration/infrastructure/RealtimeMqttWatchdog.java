package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.support.tenant.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.DisposableBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Five-second admission cadence, independently bounded I/O; Broker remains the retry fact. */
@Component
@DataPlaneDatabase
public class RealtimeMqttWatchdog implements DisposableBean {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RealtimeMqttWatchdog.class);
    private final RealtimeMqttSessions sessions;private final RealtimeMqttIdleGuard guard;
    private final Set<UUID> pending=ConcurrentHashMap.newKeySet();private final AtomicBoolean scanning=new AtomicBoolean();
    private final ThreadPoolExecutor scanner,pool;
    public RealtimeMqttWatchdog(RealtimeMqttSessions sessions,RealtimeMqttIdleGuard guard){
        this.sessions=sessions;this.guard=guard;var sequence=new AtomicInteger();
        scanner=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"tc-mqtt-session-scan");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        pool=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1000),r->{var t=new Thread(r,"tc-mqtt-session-guard-"+sequence.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=5000,initialDelay=10000)
    public void tick(){
        if(!sessions.configured()||!scanning.compareAndSet(false,true))return;
        try{scanner.execute(()->{try{
            for(var session:sessions.active())if(pending.add(session.ticket()))try{
                pool.execute(()->{try(var ignored=DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)){guard.inspect(session);}catch(RuntimeException failure){LOG.warn("公开MQTT看护将在下一次实际连接扫描重试 failureType={}",failure.getClass().getSimpleName());}finally{pending.remove(session.ticket());}});
            }catch(RejectedExecutionException saturated){pending.remove(session.ticket());break;}
        }catch(RuntimeException failure){LOG.warn("公开MQTT连接枚举暂不可用 failureType={}",failure.getClass().getSimpleName());}finally{scanning.set(false);}});}
        catch(RejectedExecutionException stopping){scanning.set(false);}
    }
    public void destroy()throws InterruptedException{scanner.shutdownNow();pool.shutdown();sessions.cancelActive();scanner.awaitTermination(5,TimeUnit.SECONDS);if(!pool.awaitTermination(10,TimeUnit.SECONDS)){pool.shutdownNow();sessions.cancelActive();pool.awaitTermination(5,TimeUnit.SECONDS);}}
}
