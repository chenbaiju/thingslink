package com.things.link.enduser.infrastructure.persistence;
import com.things.link.enduser.domain.AppRealtimeDeliveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcAppRealtimeDeliveryRepository implements AppRealtimeDeliveryRepository {
    private final JdbcTemplate jdbc;
    public JdbcAppRealtimeDeliveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public boolean lockIdentity(UUID tenant,UUID project,UUID user){
        if(jdbc.query("SELECT id FROM app_user WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR SHARE",(r,n)->r.getObject(1,UUID.class),tenant,user).isEmpty())return false;
        return !jdbc.query("SELECT id FROM app_user_role WHERE tenant_id=? AND project_id=? AND app_user_id=? AND status='ACTIVE' FOR SHARE",(r,n)->r.getObject(1,UUID.class),tenant,project,user).isEmpty();
    }
    @Override public int lockBindings(UUID tenant,UUID project,UUID user,List<UUID> devices){
        return jdbc.query("SELECT id FROM app_user_device WHERE tenant_id=? AND project_id=? AND app_user_id=? AND status='ACTIVE' AND device_id=ANY(?::uuid[]) ORDER BY device_id FOR SHARE",(r,n)->r.getObject(1,UUID.class),tenant,project,user,"{"+devices.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}").size();
    }
}
