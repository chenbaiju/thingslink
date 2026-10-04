package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.AutomationRetentionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** 每次调用独立短事务，SQL端口固定年龄、权限、锁序与上限。 */
@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class JdbcAutomationRetentionRepository implements AutomationRetentionRepository {
    private final JdbcTemplate jdbc;
    public JdbcAutomationRetentionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public List<Scope> candidates(UUID after){return jdbc.query("SELECT * FROM scan_automation_retention_projects(?)",
            (r,n)->new Scope(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class)),after);}
    @Override public int purgeInputs(){return jdbc.queryForObject("SELECT automation_purge_inputs()",Integer.class);}
    @Override public int purgeIngressRejections(){return jdbc.queryForObject("SELECT automation_purge_ingress_rejections()",Integer.class);}
    @Override public int purgeHistory(Scope scope){return jdbc.queryForObject("SELECT automation_retention_batch(?,?)",Integer.class,scope.tenantId(),scope.projectId());}
}
