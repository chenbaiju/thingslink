package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaJobCompletion;
import com.things.link.ota.domain.OtaJobCompletionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ADR0204按实际事务身份读取不可变事实；无全库扫描或回填入口。 */
@Repository
public class JdbcOtaJobCompletionRepository implements OtaJobCompletionRepository {
    private final JdbcTemplate jdbc;
    /** 使用调用方事务绑定的连接，保留真实RLS。 */
    public JdbcOtaJobCompletionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<OtaJobCompletion> page(UUID tenant, UUID project, String transaction, UUID afterJob, int limit) {
        if(tenant==null || project==null || transaction==null || !transaction.matches("[0-9]{1,20}") || limit<1 || limit>100)
            throw new IllegalArgumentException("OTA完成来源分页参数无效");
        return jdbc.query("""
                SELECT * FROM ota_job_completion WHERE tenant_id=? AND project_id=?
                    AND origin_transaction=?::xid8 AND origin_transaction=pg_current_xact_id()
                    AND (?::uuid IS NULL OR job_id>?::uuid) ORDER BY job_id LIMIT ?
                """,(rs,row)->new OtaJobCompletion(rs.getObject("job_id",UUID.class),rs.getObject("tenant_id",UUID.class),
                rs.getObject("project_id",UUID.class),rs.getObject("campaign_id",UUID.class),rs.getObject("device_id",UUID.class),
                rs.getObject("firmware_id",UUID.class),rs.getString("manifest_sha256"),rs.getString("from_status"),
                rs.getString("status"),rs.getLong("state_version"),rs.getInt("attempt_no"),rs.getString("failure_code"),
                rs.getTimestamp("completed_at").toInstant(),rs.getLong("project_generation"),rs.getString("trace_id"),
                rs.getString("origin_transaction")),tenant,project,transaction,afterJob,afterJob,limit);
    }
}
