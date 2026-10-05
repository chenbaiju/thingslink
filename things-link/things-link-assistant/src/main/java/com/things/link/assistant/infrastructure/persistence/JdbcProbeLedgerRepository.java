package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.assistant.domain.ProbeLedgerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 探针专属 JDBC 仓储；复用持久批次并以唯一约束阻止重复机会，不操作设备表。 */
@Repository
public class JdbcProbeLedgerRepository implements ProbeLedgerRepository {
    private final JdbcTemplate jdbc;
    public JdbcProbeLedgerRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<Batch> BATCH=(r,n)->new Batch(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),
        r.getObject("project_id",UUID.class),r.getString("authorization_id"),r.getString("environment_id"),
        r.getLong("configuration_revision"),r.getString("manifest_sha256"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<Attempt> ATTEMPT=(r,n)->new Attempt(r.getObject("id",UUID.class),r.getObject("batch_id",UUID.class),
        r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("created_by",UUID.class),
        r.getInt("sample_index"),Status.valueOf(r.getString("status")),r.getTimestamp("claimed_at").toInstant(),
        r.getTimestamp("deadline").toInstant(),r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant());
    /** 沿用接口定义的批次契约；授权标识冲突时读取原批次，不覆盖绑定。{@inheritDoc} */
    @Override public Optional<Batch> ensure(Batch b) {
        jdbc.update("""
            INSERT INTO assistant_probe_batch(id,tenant_id,project_id,authorization_id,environment_id,configuration_revision,manifest_sha256,created_at)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(authorization_id) DO NOTHING
            """,b.id(),b.tenantId(),b.projectId(),b.authorizationId(),b.environmentId(),b.configurationRevision(),b.manifestSha256(),Timestamp.from(b.createdAt()));
        return jdbc.query("SELECT * FROM assistant_probe_batch WHERE tenant_id=? AND project_id=? AND authorization_id=?",
            BATCH,b.tenantId(),b.projectId(),b.authorizationId()).stream().findFirst();
    }
    /** 沿用接口定义的认领契约；批次与样本唯一约束保证重复请求不能重置次数。{@inheritDoc} */
    @Override public boolean claim(Attempt a) {
        return jdbc.update("""
            INSERT INTO assistant_probe_attempt(id,batch_id,tenant_id,project_id,created_by,sample_index,status,claimed_at,deadline)
            VALUES(?,?,?,?,?,?,'CLAIMED',?,?) ON CONFLICT(batch_id,sample_index) DO NOTHING
            """,a.id(),a.batchId(),a.tenantId(),a.projectId(),a.createdBy(),a.sampleIndex(),Timestamp.from(a.claimedAt()),Timestamp.from(a.deadline()))==1;
    }
    /** 沿用接口定义的读取契约，同时限定租户、项目和创建者。{@inheritDoc} */
    @Override public Optional<Attempt> find(UUID tenant,UUID project,UUID creator,UUID id) {
        return jdbc.query("SELECT * FROM assistant_probe_attempt WHERE tenant_id=? AND project_id=? AND created_by=? AND id=?",
            ATTEMPT,tenant,project,creator,id).stream().findFirst();
    }
    /** 沿用接口定义的完成契约，仅更新仍处于认领状态的记录。{@inheritDoc} */
    @Override public boolean finish(Attempt a,Status target,Instant at) {
        return jdbc.update("""
            UPDATE assistant_probe_attempt SET status=?,finished_at=?
            WHERE tenant_id=? AND project_id=? AND created_by=? AND id=? AND status='CLAIMED'
            """,target.name(),Timestamp.from(at),a.tenantId(),a.projectId(),a.createdBy(),a.id())==1;
    }
}
