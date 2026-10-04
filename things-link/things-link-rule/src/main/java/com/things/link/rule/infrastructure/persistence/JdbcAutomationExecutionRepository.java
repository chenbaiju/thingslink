package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.AutomationExecution;
import com.things.link.rule.domain.AutomationExecutionRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 仅扫描函数可跨项目返回最小身份，其余操作受事务RLS及真实锁保护。 */
@Repository
public class JdbcAutomationExecutionRepository implements AutomationExecutionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAutomationExecutionRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public List<Candidate> candidates(){return jdbc.query("SELECT * FROM scan_automation_executions()",
            (r,n)->new Candidate(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),
                    r.getObject("automation_id",UUID.class),r.getObject("id",UUID.class)));}
    @Override public Optional<AutomationExecution> lock(Candidate c){return jdbc.query(
            "SELECT * FROM rule_automation_execution WHERE tenant_id=? AND project_id=? AND automation_id=? AND id=? FOR UPDATE",
            this::map,c.tenantId(),c.projectId(),c.automationId(),c.executionId()).stream().findFirst();}
    @Override public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    @Override public AutomationExecution claim(Candidate c){
        var e=jdbc.queryForObject("""
                UPDATE rule_automation_execution SET status='RUNNING',attempt_count=attempt_count+1,
                  lease_token=lease_token+1,lease_until=clock_timestamp()+interval '30 seconds',next_attempt_at=NULL
                WHERE project_id=? AND id=? AND status IN ('QUEUED','RETRY_WAIT') AND attempt_count<3
                  AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()) AND recovery_deadline>clock_timestamp()
                RETURNING *
                """,this::map,c.projectId(),c.executionId());
        jdbc.update("""
                INSERT INTO rule_automation_attempt(id,tenant_id,project_id,execution_id,attempt_number,lease_token,started_at,outcome)
                VALUES(?,?,?,?,?,?,clock_timestamp(),'STARTED')
                """,Uuid7.generate(),e.tenantId(),e.projectId(),e.id(),e.attempt(),e.token());
        return e;
    }
    @Override public boolean finish(AutomationExecution e,String status,String reason){
        int changed=jdbc.update("""
                UPDATE rule_automation_execution SET status=?,reason_code=?,completed_at=clock_timestamp(),lease_until=NULL,next_attempt_at=NULL
                WHERE project_id=? AND id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
                  AND (?<>'DISPATCHED' OR recovery_deadline>clock_timestamp())
                """,status,reason,e.projectId(),e.id(),e.token(),status);
        if(changed==1)endAttempt(e,status.equals("DISPATCHED")||status.equals("SKIPPED")?"SUCCEEDED":"FAILED",reason);
        return changed==1;
    }
    @Override public boolean retry(AutomationExecution e){
        if(e.attempt()>=3||!now().isBefore(e.deadline()))return finish(e,"FAILED","RECOVERY_EXHAUSTED");
        int changed=jdbc.update("""
                UPDATE rule_automation_execution SET status='RETRY_WAIT',reason_code='DEPENDENCY_TRANSIENT',lease_until=NULL,
                  next_attempt_at=clock_timestamp()+make_interval(secs=>CASE WHEN attempt_count=1 THEN 60 ELSE 300 END)
                WHERE project_id=? AND id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
                """,e.projectId(),e.id(),e.token());
        if(changed==1)endAttempt(e,"FAILED","DEPENDENCY_TRANSIENT");return changed==1;
    }
    @Override public void expire(AutomationExecution e){
        // 调用者已持项目/定义/执行锁；只关闭真正过期或恢复期限耗尽的事实。
        int changed=jdbc.update("""
                UPDATE rule_automation_execution SET status=CASE WHEN attempt_count>=3 OR recovery_deadline<=clock_timestamp() THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                  reason_code=CASE WHEN attempt_count>=3 OR recovery_deadline<=clock_timestamp() THEN 'RECOVERY_EXHAUSTED' ELSE 'DEPENDENCY_TRANSIENT' END,
                  completed_at=CASE WHEN attempt_count>=3 OR recovery_deadline<=clock_timestamp() THEN clock_timestamp() ELSE NULL END,
                  next_attempt_at=CASE WHEN attempt_count>=3 OR recovery_deadline<=clock_timestamp() THEN NULL
                    ELSE clock_timestamp()+make_interval(secs=>CASE WHEN attempt_count=1 THEN 60 ELSE 300 END) END,lease_until=NULL
                WHERE project_id=? AND id=? AND lease_token=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')
                  AND ((status='RUNNING' AND lease_until<=clock_timestamp()) OR recovery_deadline<=clock_timestamp())
                """,e.projectId(),e.id(),e.token());
        if(changed==1&&e.status().equals("RUNNING"))endAttempt(e,"LEASE_EXPIRED","DEPENDENCY_TRANSIENT");
    }
    private void endAttempt(AutomationExecution e,String outcome,String reason){
        if(jdbc.update("UPDATE rule_automation_attempt SET outcome=?,reason_code=?,finished_at=clock_timestamp() WHERE project_id=? AND execution_id=? AND lease_token=? AND outcome='STARTED'",
                outcome,reason,e.projectId(),e.id(),e.token())!=1)throw new IllegalStateException("自动化尝试事实不一致");
    }
    private AutomationExecution map(ResultSet r,int n)throws SQLException{return new AutomationExecution(
            r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),
            r.getObject("automation_id",UUID.class),r.getObject("automation_version_id",UUID.class),r.getObject("device_id",UUID.class),
            r.getObject("responsible_account_id",UUID.class),r.getString("trace_id"),r.getString("input_snapshot")==null?null:precise(r.getString("input_snapshot")),
            time(r,"occurred_at"),time(r,"accepted_at"),r.getString("status"),r.getInt("attempt_count"),r.getLong("lease_token"),
            time(r,"lease_until"),time(r,"next_attempt_at"),time(r,"recovery_deadline"));}
    private Instant time(ResultSet r,String column)throws SQLException{var v=r.getTimestamp(column);return v==null?null:v.toInstant();}
    /** 冻结配置和输入不得经double回读而丢失数值语义。 */
    private tools.jackson.databind.JsonNode precise(String value){
        return json.readerFor(tools.jackson.databind.JsonNode.class)
                .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);
    }

}
