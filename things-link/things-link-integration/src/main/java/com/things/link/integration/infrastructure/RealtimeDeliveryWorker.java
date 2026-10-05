package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.support.tenant.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** 有界公开实时交付工作器；最多四个并发任务，饱和时停止本轮提交并保留持久恢复。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.integration.realtime.enabled",havingValue="true")
public class RealtimeDeliveryWorker implements DisposableBean {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RealtimeDeliveryWorker.class);
    private final RealtimeDeliveryState state;private final RealtimeDeliveryDispatcher dispatcher;private final RealtimeMqttPublisher publisher;
    private final ThreadPoolExecutor workers;
    public RealtimeDeliveryWorker(RealtimeDeliveryState state,RealtimeDeliveryDispatcher dispatcher,RealtimeMqttPublisher publisher){
        this.state=state;this.dispatcher=dispatcher;this.publisher=publisher;var sequence=new AtomicInteger();
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"tc-public-realtime-"+sequence.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=1000,initialDelay=10000)
    public void tick(){
        try{for(var c:state.candidates()){
            try{workers.execute(()->{try(var ignored=DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)){dispatcher.dispatch(c);}catch(RuntimeException failure){LOG.warn("公开实时发送保留持久恢复 failureType={}",failure.getClass().getSimpleName());}});}
            catch(RejectedExecutionException saturated){break;}
        }}catch(RuntimeException failure){LOG.warn("公开实时交付扫描暂不可用 failureType={}",failure.getClass().getSimpleName());}
    }
    public void destroy()throws InterruptedException{workers.shutdown();publisher.cancelActive();if(!workers.awaitTermination(10,TimeUnit.SECONDS)){workers.shutdownNow();publisher.cancelActive();workers.awaitTermination(5,TimeUnit.SECONDS);}}
}
