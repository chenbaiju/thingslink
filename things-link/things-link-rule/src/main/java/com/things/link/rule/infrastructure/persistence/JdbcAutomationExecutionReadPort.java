package com.things.link.rule.infrastructure.persistence;
import com.things.link.rule.application.automation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** 不选择input_snapshot或配置列，所有条件都参数绑定。 */
@Repository
public class JdbcAutomationExecutionReadPort implements AutomationExecutionReadPort {
    private final JdbcTemplate jdbc;
    public JdbcAutomationExecutionReadPort(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String SELECT="SELECT id,automation_id,automation_version_id,trigger_type,device_id,status,reason_code,attempt_count,occurred_at,accepted_at,created_at,completed_at FROM rule_automation_execution WHERE project_id=?";
    @Override public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    @Override public List<AutomationExecutionView> page(UUID project,UUID automation,String status,Instant from,Instant to,Instant before,UUID beforeId,int limit){
        String sql=SELECT+" AND created_at>=? AND created_at<?";List<Object> args=new ArrayList<>(List.of(project,Timestamp.from(from),Timestamp.from(to)));
        if(automation!=null){sql+=" AND automation_id=?";args.add(automation);}
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(before!=null){sql+=" AND (created_at,id)<(?,?)";args.add(Timestamp.from(before));args.add(beforeId);}
        args.add(limit);return jdbc.query(sql+" ORDER BY created_at DESC,id DESC LIMIT ?",this::view,args.toArray());
    }
    @Override public Optional<AutomationExecutionDetailView> detail(UUID project,UUID id){
        var rows=jdbc.query(SELECT+" AND id=?",this::view,project,id);if(rows.isEmpty())return Optional.empty();
        var attempts=jdbc.query("SELECT attempt_number,started_at,finished_at,outcome,reason_code FROM rule_automation_attempt WHERE project_id=? AND execution_id=? ORDER BY attempt_number LIMIT 3",
                (r,i)->new AutomationExecutionDetailView.Attempt(r.getInt(1),instant(r,"started_at"),instant(r,"finished_at"),r.getString(4),r.getString(5)),project,id);
        var notifications=jdbc.query("SELECT channel,status,attempt_count,last_error_code,delivered_at FROM rule_notification_delivery WHERE project_id=? AND automation_execution_id=? ORDER BY created_at,id LIMIT 32",
                (r,i)->new AutomationExecutionDetailView.Notification(r.getString(1),r.getString(2),r.getInt(3),r.getString(4),instant(r,"delivered_at")),project,id);
        var devices=jdbc.query("SELECT device_id,operation_type,command_id,status,failure_code,completed_at FROM rule_device_action_delivery WHERE project_id=? AND automation_execution_id=? ORDER BY created_at,id LIMIT 32",
                (r,i)->new AutomationExecutionDetailView.DeviceAction(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getString(4),r.getString(5),instant(r,"completed_at")),project,id);
        return Optional.of(new AutomationExecutionDetailView(rows.getFirst(),attempts,notifications,devices));
    }
    private AutomationExecutionView view(ResultSet r,int i)throws SQLException{return new AutomationExecutionView(r.getObject("id",UUID.class),r.getObject("automation_id",UUID.class),r.getObject("automation_version_id",UUID.class),r.getString("trigger_type"),r.getObject("device_id",UUID.class),r.getString("status"),r.getString("reason_code"),r.getInt("attempt_count"),instant(r,"occurred_at"),instant(r,"accepted_at"),instant(r,"created_at"),instant(r,"completed_at"));}
    private static Instant instant(ResultSet r,String column)throws SQLException{var value=r.getTimestamp(column);return value==null?null:value.toInstant();}
}
