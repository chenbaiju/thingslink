package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.support.tenant.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.integration.webhook.enabled",havingValue="true")
public class WebhookDeliveryWorker implements DisposableBean {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private final WebhookDeliveryState state;private final WebhookDeliveryDispatcher dispatcher;
    private final ThreadPoolExecutor workers;
    public WebhookDeliveryWorker(WebhookDeliveryState state,WebhookDeliveryDispatcher dispatcher){
        this.state=state;this.dispatcher=dispatcher;var sequence=new AtomicInteger();
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"tc-public-webhook-"+sequence.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=1000,initialDelay=10000)
    public void tick(){
        try{for(var c:state.candidates()){
            try{workers.execute(()->{try(var ignored=DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)){dispatcher.dispatch(c);}catch(RuntimeException failure){LOG.warn("公开Webhook发送保留持久恢复 failureType={}",failure.getClass().getSimpleName());}});}
            catch(RejectedExecutionException saturated){break;}
        }}catch(RuntimeException failure){LOG.warn("公开Webhook交付扫描暂不可用 failureType={}",failure.getClass().getSimpleName());}
    }
    public void destroy()throws InterruptedException{workers.shutdown();if(!workers.awaitTermination(10,TimeUnit.SECONDS)){workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}}
}
