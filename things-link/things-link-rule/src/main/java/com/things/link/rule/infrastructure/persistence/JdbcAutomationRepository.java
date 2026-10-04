package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 只访问本域自动化表；版本不可变性及复合归属由数据库兜底。 */
@Repository
public class JdbcAutomationRepository implements AutomationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAutomationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public Optional<AutomationDefinition> find(UUID project,UUID id,boolean lock){
        return jdbc.query("SELECT * FROM rule_automation WHERE project_id=? AND id=? AND deleted_at IS NULL"+(lock?" FOR UPDATE":""),this::definition,project,id).stream().findFirst();
    }
    @Override public Optional<AutomationVersion> version(UUID project,UUID id,UUID version){
        return jdbc.query("SELECT * FROM rule_automation_version WHERE project_id=? AND automation_id=? AND id=?",this::version,project,id,version).stream().findFirst();
    }
    @Override public List<AutomationVersion> matching(UUID project,UUID device){
        return jdbc.query("""
                SELECT v.* FROM rule_automation a JOIN rule_automation_version v ON v.project_id=a.project_id AND v.automation_id=a.id AND v.id=a.active_version_id
                WHERE a.project_id=? AND a.deleted_at IS NULL AND a.status='ACTIVE'
                 AND v.trigger_type='PROPERTY_REPORTED' AND v.trigger_config->>'deviceId'=? ORDER BY a.id
                """,this::version,project,device.toString());
    }
    @Override public List<AutomationDefinition> page(UUID project,String name,String status,Instant before,UUID beforeId,int limit){
        String sql="SELECT * FROM rule_automation WHERE project_id=? AND deleted_at IS NULL AND strpos(lower(name),lower(?))>0";
        List<Object> args=new ArrayList<>(List.of(project,name));
        if(!status.isEmpty()){sql+=" AND status=?";args.add(status);}
        if(before!=null){sql+=" AND (created_at,id)<(?,?)";args.add(time(before));args.add(beforeId);}
        args.add(limit);return jdbc.query(sql+" ORDER BY created_at DESC,id DESC LIMIT ?",this::definition,args.toArray());
    }
    @Override public List<AutomationVersion> history(UUID project,UUID id,Long before,int limit){
        String sql="SELECT * FROM rule_automation_version WHERE project_id=? AND automation_id=?";
        List<Object> args=new ArrayList<>(List.of(project,id));
        if(before!=null){sql+=" AND version_number<?";args.add(before);}
        args.add(limit);return jdbc.query(sql+" ORDER BY version_number DESC LIMIT ?",this::version,args.toArray());
    }
    @Override public int countDefinitions(UUID project){return jdbc.queryForObject("SELECT count(*) FROM rule_automation WHERE project_id=? AND deleted_at IS NULL",Integer.class,project);}
    @Override public int countActiveSubscriptions(UUID project,UUID device,UUID excluding){
        return jdbc.queryForObject("""
                SELECT count(*) FROM rule_automation a JOIN rule_automation_version v ON v.project_id=a.project_id AND v.automation_id=a.id AND v.id=a.active_version_id
                WHERE a.project_id=? AND a.deleted_at IS NULL AND a.status='ACTIVE' AND a.id<>?
                  AND v.trigger_type='PROPERTY_REPORTED' AND v.trigger_config->>'deviceId'=?
                """,Integer.class,project,excluding,device.toString());
    }
    @Override public int countPending(UUID project){return jdbc.queryForObject("SELECT count(*) FROM rule_automation_execution WHERE project_id=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')",Integer.class,project);}
    @Override public long nextVersion(UUID project,UUID id){return jdbc.queryForObject("SELECT COALESCE(max(version_number),0)+1 FROM rule_automation_version WHERE project_id=? AND automation_id=?",Long.class,project,id);}
    @Override public void create(AutomationDefinition d,AutomationVersion v){
        jdbc.update("""
                INSERT INTO rule_automation(id,tenant_id,project_id,name,description,status,version,created_by,created_at,updated_at)
                VALUES(?,?,?,?,?,'DRAFT',1,?,?,?)
                """,d.id(),d.tenantId(),d.projectId(),d.name(),d.description(),d.createdBy(),time(d.createdAt()),time(d.updatedAt()));
        insertVersion(v);
    }
    @Override public boolean revise(AutomationDefinition d,long expected,AutomationVersion v){
        int updated=jdbc.update("UPDATE rule_automation SET name=?,description=?,version=version+1,updated_at=clock_timestamp() WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL",d.name(),d.description(),d.projectId(),d.id(),expected);
        if(updated==1)insertVersion(v);return updated==1;
    }
    @Override public boolean activate(UUID project,UUID id,UUID version,long expected){return jdbc.update("UPDATE rule_automation SET active_version_id=?,status='ACTIVE',version=version+1,updated_at=clock_timestamp() WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL",version,project,id,expected)==1;}
    @Override public boolean pause(UUID project,UUID id,long expected){return jdbc.update("UPDATE rule_automation SET status='PAUSED',version=version+1,updated_at=clock_timestamp() WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL AND status='ACTIVE'",project,id,expected)==1;}
    @Override public boolean delete(UUID project,UUID id,long expected){return jdbc.update("UPDATE rule_automation SET deleted_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp() WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL",project,id,expected)==1;}
    @Override public void admit(UUID id,AutomationVersion v,UUID sourceEvent,UUID device,JsonNode input,String digest,String trace,String reason,Instant now,Instant occurredAt,Instant acceptedAt){
        jdbc.update("""
                INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,
                  occurrence_key,source_event_id,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,status,
                  reason_code,created_at,completed_at,recovery_deadline,occurred_at,accepted_at)
                VALUES(?,?,?,?,?,'PROPERTY_REPORTED',?,?,?,?::jsonb,?,?,?, ?,?,?,?,?,?,?)
                """,id,v.tenantId(),v.projectId(),v.automationId(),v.id(),"event:"+sourceEvent,sourceEvent,device,
                json.writeValueAsString(input),digest,v.createdBy(),trace,reason==null?"QUEUED":"REJECTED",reason,time(now),
                reason==null?null:time(now),time(now.plusSeconds(86400)),time(occurredAt),time(acceptedAt));
    }
    @Override public void admitTime(UUID id,AutomationVersion v,Instant fireAt,UUID device,JsonNode input,String digest,String reason,Instant now){
        jdbc.update("""
            INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,
              occurrence_key,scheduled_fire_at,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,status,
              reason_code,created_at,completed_at,recovery_deadline,occurred_at,accepted_at)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?, ?,?,?,?,?,?,?)
            """,id,v.tenantId(),v.projectId(),v.automationId(),v.id(),v.triggerType(),"time:"+v.id()+":"+fireAt, time(fireAt),device,
            json.writeValueAsString(input),digest,v.createdBy(),id.toString(),reason==null?"QUEUED":"REJECTED",reason,time(now),
            reason==null?null:time(now),time(now.plusSeconds(86400)),time(fireAt),time(now));
    }
    private void insertVersion(AutomationVersion v){
        jdbc.update("""
                INSERT INTO rule_automation_version(id,tenant_id,project_id,automation_id,version_number,trigger_type,trigger_config,conditions,actions,created_by,created_at)
                VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)
                """,v.id(),v.tenantId(),v.projectId(),v.automationId(),v.versionNumber(),v.triggerType(),json.writeValueAsString(v.triggerConfig()),json.writeValueAsString(v.conditions()),json.writeValueAsString(v.actions()),v.createdBy(),time(v.createdAt()));
    }
    private AutomationDefinition definition(ResultSet r,int i)throws SQLException{return new AutomationDefinition(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getString("name"),r.getString("description"),AutomationDefinition.Status.valueOf(r.getString("status")),r.getObject("active_version_id",UUID.class),r.getLong("version"),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getTimestamp("deleted_at")==null?null:r.getTimestamp("deleted_at").toInstant());}
    private AutomationVersion version(ResultSet r,int i)throws SQLException{return new AutomationVersion(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("automation_id",UUID.class),r.getLong("version_number"),r.getString("trigger_type"),precise(r.getString("trigger_config")),precise(r.getString("conditions")),precise(r.getString("actions")),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant());}
    private static Timestamp time(Instant value){return Timestamp.from(value);}
    /** 冻结配置和输入不得经double回读而丢失数值语义。 */
    private tools.jackson.databind.JsonNode precise(String value){
        return json.readerFor(tools.jackson.databind.JsonNode.class)
                .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);
    }

}
