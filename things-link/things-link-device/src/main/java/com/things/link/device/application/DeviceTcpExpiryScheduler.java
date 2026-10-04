package com.things.link.device.application;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
/** A surviving instance closes expired persistent TCP sessions; no local socket or finally block is required. */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.device.tcp-expiry.enabled",havingValue="true",matchIfMissing=true)
public class DeviceTcpExpiryScheduler {
    private static final Logger log=LoggerFactory.getLogger(DeviceTcpExpiryScheduler.class);
    private final JdbcTemplate jdbc;private final DeviceTcpSessionService sessions;
    public DeviceTcpExpiryScheduler(JdbcTemplate jdbc,DeviceTcpSessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}
    @Scheduled(initialDelayString="${things-link.device.tcp-expiry.initial-delay-millis:1000}",fixedDelayString="${things-link.device.tcp-expiry.fixed-delay-millis:1000}",scheduler="maintenanceScheduler")
    public void tick(){var candidates=jdbc.query("SELECT * FROM dev_tcp_expiry_candidates(100)",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)));
        for(var c:candidates)try{sessions.expire(c.tenant(),c.project(),c.device());}catch(RuntimeException e){log.warn("TCP expiry transaction failed; retry next scan ({})",e.getClass().getSimpleName());}}
    private record Candidate(UUID tenant,UUID project,UUID device){}
}
