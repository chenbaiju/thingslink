package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
/** 公开准入或发送被禁用时仍保留维护任务；该任务不发起 HTTP 调用。 */
@Component
@DataPlaneDatabase
public class WebhookRetentionMaintenance {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(WebhookRetentionMaintenance.class);
    private final WebhookDeliveryState deliveries;private final WebhookRetentionService retention;
    public WebhookRetentionMaintenance(WebhookDeliveryState deliveries,WebhookRetentionService retention){this.deliveries=deliveries;this.retention=retention;}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=60000,initialDelay=60000)
    public void tick(){
        int remaining=500;
        try{for(var candidate:deliveries.candidates()){
            if(remaining<2)break;remaining-=2;
            try{deliveries.claim(candidate,false);}catch(RuntimeException failure){warn(failure);}
        }}catch(RuntimeException failure){warn(failure);}
        try{for(var scope:retention.candidates()){
            if(remaining<2)break;
            try{remaining-=retention.purge(scope,remaining);}catch(RuntimeException failure){remaining=0;warn(failure);/* 提交结果可能不确定：禁止再次消费此预算。 */}
        }}catch(RuntimeException failure){warn(failure);}
    }
    private static void warn(RuntimeException failure){LOG.warn("公开Webhook维护暂不可用 failureType={}",failure.getClass().getSimpleName());}
}
