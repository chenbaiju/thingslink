package com.things.link.rule.infrastructure.persistence;
import com.things.link.rule.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** SQL只访问rule域；当前版本和历史投递事实分别约束完整所属关系。 */
@Repository
public class JdbcDeviceMessageRuleRepository implements DeviceMessageRuleRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceMessageRuleRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Override public List<DeviceMessageRuleItem> definitions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit) {
        String sql="""
                SELECT a.id,a.name,a.status,a.active_version_id,v.version_number,a.created_at,
                       (v.actions @> '[{"nodeType":"device-command-action"}]'::jsonb
                        OR v.actions @> '[{"nodeType":"device-property-set-action"}]'::jsonb) AS has_device_action
                  FROM rule_message a JOIN rule_version v
                    ON v.tenant_id=a.tenant_id AND v.project_id=a.project_id AND v.rule_id=a.id AND v.id=a.active_version_id
                 WHERE a.project_id=?
                   AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','PAUSED')
                """;
        List<Object> args=new ArrayList<>(List.of(project));
        sql+=keyset("a.created_at","a.id",beforeTime,beforeId,limit,args);
        return jdbc.query(sql,(r,n)->new DeviceMessageRuleItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("status"),
                r.getObject("active_version_id",UUID.class),r.getLong("version_number"),"PROJECT_CANDIDATE",
                r.getBoolean("has_device_action"),r.getTimestamp("created_at").toInstant()),args.toArray());
    }
    @Override public List<DeviceMessageRuleExecutionItem> history(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit) {
        String sql="SELECT id,rule_id,rule_version_id,message_id,attempt,status,result_code,duration_millis,input_bytes,output_bytes,created_at FROM rule_execution_log WHERE project_id=? AND device_id=?";
        List<Object> args=new ArrayList<>(List.of(project,device));
        sql+=keyset("created_at","id",beforeTime,beforeId,limit,args);
        return jdbc.query(sql,(r,n)->new DeviceMessageRuleExecutionItem(r.getObject("id",UUID.class),
                r.getObject("rule_id",UUID.class),r.getObject("rule_version_id",UUID.class),r.getObject("message_id",UUID.class),
                r.getInt("attempt"),r.getString("status"),r.getString("result_code"),r.getLong("duration_millis"),
                r.getInt("input_bytes"),r.getInt("output_bytes"),r.getTimestamp("created_at").toInstant()),args.toArray());
    }
    @Override public List<DeviceMessageRuleActionItem> actions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit) {
        String sql="SELECT id,rule_id,rule_version_id,message_id,operation_type,status,command_id,failure_code,created_at,completed_at FROM rule_device_action_delivery WHERE project_id=? AND device_id=? AND rule_id IS NOT NULL";
        List<Object> args=new ArrayList<>(List.of(project,device));
        sql+=keyset("created_at","id",beforeTime,beforeId,limit,args);
        return jdbc.query(sql,(r,n)->new DeviceMessageRuleActionItem(r.getObject("id",UUID.class),
                r.getObject("rule_id",UUID.class),r.getObject("rule_version_id",UUID.class),r.getObject("message_id",UUID.class),
                r.getString("operation_type"),r.getString("status"),r.getObject("command_id",UUID.class),r.getString("failure_code"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant()),args.toArray());
    }
    private String keyset(String timeColumn,String idColumn,Instant time,UUID id,int limit,List<Object> args) {
        if((time==null)!=(id==null)||limit<1||limit>51)throw new IllegalArgumentException("消息规则设备分页边界不合法");
        String sql="";
        if(time!=null){sql=" AND ("+timeColumn+","+idColumn+") < (?,?)";args.add(Timestamp.from(time));args.add(id);}
        args.add(limit);return sql+" ORDER BY "+timeColumn+" DESC,"+idColumn+" DESC LIMIT ?";
    }
}
