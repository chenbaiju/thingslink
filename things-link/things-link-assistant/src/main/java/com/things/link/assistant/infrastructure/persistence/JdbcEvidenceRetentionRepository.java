package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.EvidenceRetentionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** 候选只返回身份；删除仍由普通APP双轴隔离执行，不取得正文或更新权限。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcEvidenceRetentionRepository implements EvidenceRetentionRepository {
    private final JdbcTemplate jdbc;
    public JdbcEvidenceRetentionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 沿用接口的固定身份扫描合同。{@inheritDoc} */
    @Override public List<Scope> candidates(UUID afterProject) {
        return jdbc.query("SELECT * FROM assistant_evidence_retention_scopes(?)",
                (r,n)->new Scope(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),afterProject);
    }

    /** 沿用接口的事务及限批合同；不可变表不申请更新权限。{@inheritDoc} */
    @Override public int deleteExpired(Scope scope, int limit) {
        if (limit<1 || limit>500) throw new IllegalArgumentException("事实回收预算必须为1至500");
        return jdbc.update("DELETE FROM assistant_evidence_record WHERE tenant_id=? AND project_id=? AND id IN ("
                + "SELECT id FROM assistant_evidence_record WHERE tenant_id=? AND project_id=?"
                + " AND expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT ?)",
                scope.tenantId(),scope.projectId(),scope.tenantId(),scope.projectId(),limit);
    }
}
