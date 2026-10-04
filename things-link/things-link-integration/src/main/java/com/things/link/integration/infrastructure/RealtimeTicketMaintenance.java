package com.things.link.integration.infrastructure;
import com.things.link.integration.application.RealtimeTicketService;
import com.things.link.integration.application.RealtimeTicketMaintenanceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.things.link.support.tenant.DataPlaneDatabase;
/** 无外部发送；每片候选失败不阻断其他票据，秘密与数据不写日志。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.integration.realtime.enabled",havingValue="true")
public class RealtimeTicketMaintenance {
    private final RealtimeTicketService service;private final RealtimeTicketMaintenanceService maintenance;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RealtimeTicketMaintenance.class);
    public RealtimeTicketMaintenance(RealtimeTicketService service,RealtimeTicketMaintenanceService maintenance){this.service=service;this.maintenance=maintenance;}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=10000,initialDelay=10000)
    public void tick(){
        try {for(var candidate:service.candidates())try{maintenance.maintain(candidate);}catch(RuntimeException failure){LOG.warn("公开实时票据维护暂不可用 failureType={}",failure.getClass().getSimpleName());}

        }catch(RuntimeException failure){LOG.warn("公开实时票据扫描暂不可用 failureType={}",failure.getClass().getSimpleName());}
    }
}
