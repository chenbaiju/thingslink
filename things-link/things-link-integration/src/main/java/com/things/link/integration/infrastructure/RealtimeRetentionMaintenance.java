package com.things.link.integration.infrastructure;
import com.things.link.integration.application.RealtimeTicketService;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
/** Retention is independent of the public admission feature switch. */
@Component
@DataPlaneDatabase
public class RealtimeRetentionMaintenance {
    private final RealtimeTicketService tickets;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RealtimeRetentionMaintenance.class);
    public RealtimeRetentionMaintenance(RealtimeTicketService tickets){this.tickets=tickets;}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=60000,initialDelay=60000)
    public void tick(){try{tickets.purgeExpired();}catch(RuntimeException failure){LOG.warn("公开实时保留清理暂不可用 failureType={}",failure.getClass().getSimpleName());}}
}
