package com.things.link.rule.infrastructure.persistence;
import com.things.link.rule.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** SQL只访问rule域；当前版本和历史投递事实分别约束完整所属关系。 */
@Repository
public class JdbcDeviceSceneRepository implements DeviceSceneRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceSceneRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Override public List<DeviceSceneItem> definitions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit) {
        String sql="""
                SELECT a.id,a.name,a.status,a.active_version_id,v.version_number,a.created_at,
                       jsonb_array_length(v.conditions)>0 AS uses_condition_input,
                       (v.actions @> '[{"nodeType":"device-command-action"}]'::jsonb
                        OR v.actions @> '[{"nodeType":"device-property-set-action"}]'::jsonb) AS has_device_action
                  FROM rule_scene a JOIN rule_scene_version v
                    ON v.tenant_id=a.tenant_id AND v.project_id=a.project_id AND v.scene_id=a.id AND v.id=a.active_version_id
                 WHERE a.project_id=?
                   AND a.deleted_at IS NULL AND a.status IN ('ACTIVE','PAUSED')
                """;
        List<Object> args=new ArrayList<>(List.of(project));
        sql+=keyset("a.created_at","a.id",beforeTime,beforeId,limit,args);
        return jdbc.query(sql,(r,n)->new DeviceSceneItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("status"),
                r.getObject("active_version_id",UUID.class),r.getLong("version_number"),"PROJECT_CANDIDATE",
                r.getBoolean("uses_condition_input"),r.getBoolean("has_device_action"),r.getTimestamp("created_at").toInstant()),args.toArray());
    }
    @Override public List<DeviceSceneExecutionItem> history(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit) {
        String sql="""
                SELECT e.id,e.scene_id,e.scene_version_id,e.status,
                       e.occurred_at,e.created_at,e.completed_at,
                       (SELECT count(*) FROM rule_device_action_delivery d
                         WHERE d.tenant_id=e.tenant_id AND d.project_id=e.project_id AND d.scene_id=e.scene_id
                           AND d.scene_version_id=e.scene_version_id AND d.scene_execution_id=e.id
                           AND d.device_id=e.device_id) AS device_action_record_count
                  FROM rule_scene_execution e WHERE e.project_id=? AND e.device_id=?
                """;
        List<Object> args=new ArrayList<>(List.of(project,device));
        sql+=keyset("e.created_at","e.id",beforeTime,beforeId,limit,args);
        return jdbc.query(sql,(r,n)->new DeviceSceneExecutionItem(r.getObject("id",UUID.class),
                r.getObject("scene_id",UUID.class),r.getObject("scene_version_id",UUID.class),
                r.getString("status"),r.getLong("device_action_record_count"),
                r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant()),args.toArray());
    }
    private String keyset(String timeColumn,String idColumn,Instant time,UUID id,int limit,List<Object> args) {
        if((time==null)!=(id==null)||limit<1||limit>51)throw new IllegalArgumentException("场景设备分页边界不合法");
        String sql="";
        if(time!=null){sql=" AND ("+timeColumn+","+idColumn+") < (?,?)";args.add(Timestamp.from(time));args.add(id);}
        args.add(limit);return sql+" ORDER BY "+timeColumn+" DESC,"+idColumn+" DESC LIMIT ?";
    }
}
