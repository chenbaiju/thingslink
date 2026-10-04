package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcAutomationScheduleRepository implements AutomationScheduleRepository {
    private final JdbcTemplate jdbc;
    public JdbcAutomationScheduleRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Instant nextFire(UUID project,UUID automation){return jdbc.query("SELECT next_fire_at FROM rule_automation_schedule WHERE project_id=? AND automation_id=?",(r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toInstant(),project,automation).stream().filter(Objects::nonNull).findFirst().orElse(null);}
    @Override public void boundedWait(){jdbc.execute("SET LOCAL lock_timeout='500ms'");}
    @Override public List<Candidate> candidates(UUID afterTenant){return jdbc.query("SELECT * FROM scan_automation_schedules(?)",
        (r,n)->new Candidate(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("automation_id",UUID.class)),afterTenant);}
    @Override public Optional<Claim> lock(Candidate c){return jdbc.query("SELECT * FROM rule_automation_schedule WHERE tenant_id=? AND project_id=? AND automation_id=? FOR UPDATE",
        (r,n)->map(c,r),c.tenantId(),c.projectId(),c.automationId()).stream().findFirst();}
    @Override public Optional<Claim> claim(Candidate c){return jdbc.query("""
        UPDATE rule_automation_schedule SET lease_token=lease_token+1,lease_until=clock_timestamp()+interval '30 seconds'
        WHERE tenant_id=? AND project_id=? AND automation_id=? AND next_fire_at<=clock_timestamp()
          AND (lease_until IS NULL OR lease_until<=clock_timestamp()) RETURNING *
        """,(r,n)->map(c,r),c.tenantId(),c.projectId(),c.automationId()).stream().findFirst();}
    @Override public boolean advance(Claim c,Instant next,boolean oneShot){
        int changed=jdbc.update("""
            UPDATE rule_automation_schedule SET next_fire_at=?,lease_until=NULL
            WHERE project_id=? AND automation_id=? AND automation_version_id=? AND lease_token=?
              AND next_fire_at=? AND lease_until>clock_timestamp()
            """,next==null?null:Timestamp.from(next),c.candidate().projectId(),c.candidate().automationId(),c.versionId(),c.token(),Timestamp.from(c.fireAt()));
        if(changed!=1)return false;
        jdbc.update("""
            UPDATE rule_automation_schedule_state SET next_floor_at=greatest(next_floor_at,?),one_shot_consumed=one_shot_consumed OR ?
            WHERE project_id=? AND automation_version_id=?
            """,Timestamp.from(next==null?c.fireAt().plusNanos(1000):next),oneShot,c.candidate().projectId(),c.versionId());
        return true;
    }
    private Claim map(Candidate c,java.sql.ResultSet r)throws java.sql.SQLException{
        var fire=r.getTimestamp("next_fire_at");var lease=r.getTimestamp("lease_until");
        return new Claim(c,r.getObject("automation_version_id",UUID.class),fire==null?null:fire.toInstant(),r.getLong("lease_token"),lease==null?null:lease.toInstant());
    }
    @Override public Optional<State> state(UUID project,UUID version){return jdbc.query(
        "SELECT next_floor_at,one_shot_consumed FROM rule_automation_schedule_state WHERE project_id=? AND automation_version_id=? FOR UPDATE",
        (r,n)->new State(r.getTimestamp(1).toInstant(),r.getBoolean(2)),project,version).stream().findFirst();}
    @Override public void activate(AutomationVersion v,Instant next){
        jdbc.update("""
            INSERT INTO rule_automation_schedule_state(tenant_id,project_id,automation_id,automation_version_id,next_floor_at)
            VALUES(?,?,?,?,?) ON CONFLICT(project_id,automation_version_id) DO UPDATE
            SET next_floor_at=greatest(rule_automation_schedule_state.next_floor_at,excluded.next_floor_at)
            """,v.tenantId(),v.projectId(),v.automationId(),v.id(),Timestamp.from(next));
        jdbc.update("""
            INSERT INTO rule_automation_schedule(tenant_id,project_id,automation_id,automation_version_id,next_fire_at,lease_token)
            VALUES(?,?,?,?,?,1) ON CONFLICT(project_id,automation_id) DO UPDATE
            SET automation_version_id=excluded.automation_version_id,next_fire_at=excluded.next_fire_at,
                lease_token=rule_automation_schedule.lease_token+1,lease_until=NULL
            """,v.tenantId(),v.projectId(),v.automationId(),v.id(),Timestamp.from(next));
    }
    @Override public void clear(UUID project,UUID automation){jdbc.update(
        "UPDATE rule_automation_schedule SET next_fire_at=NULL,lease_token=lease_token+1,lease_until=NULL WHERE project_id=? AND automation_id=?",project,automation);}
}
