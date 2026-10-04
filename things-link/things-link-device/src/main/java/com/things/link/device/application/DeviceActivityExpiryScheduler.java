package com.things.link.device.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** Bounded maintenance, independent of the public Webhook feature switch. */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.device.activity-expiry.enabled",havingValue="true",matchIfMissing=true)
public class DeviceActivityExpiryScheduler {
    private static final Logger log=LoggerFactory.getLogger(DeviceActivityExpiryScheduler.class);
    private final JdbcTemplate jdbc;
    private final DeviceAccessActivityService activity;
    public DeviceActivityExpiryScheduler(JdbcTemplate jdbc,DeviceAccessActivityService activity){this.jdbc=jdbc;this.activity=activity;}
    @Scheduled(initialDelayString="${things-link.device.activity-expiry.initial-delay-millis:1000}",
        fixedDelayString="${things-link.device.activity-expiry.fixed-delay-millis:1000}",scheduler="maintenanceScheduler")
    public void tick(){
        var candidates=jdbc.query("SELECT * FROM dev_activity_expiry_candidates(100)",
            (r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)));
        for(var c:candidates)try{activity.expire(c.tenant(),c.project(),c.device());}
        catch(RuntimeException failure){log.warn("Device activity expiry transaction failed; retry next scan ({})",failure.getClass().getSimpleName());}
    }
    private record Candidate(UUID tenant,UUID project,UUID device){}
}
