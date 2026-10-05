package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisCallRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 分析调用元数据 JDBC 实现，按完整身份范围读写并限制过期清理批量。 */
@Repository
public class JdbcAnalysisCallRepository implements AnalysisCallRepository {
    private final JdbcTemplate jdbc;
    public JdbcAnalysisCallRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static final RowMapper<AnalysisCall> ROW = (r, n) -> new AnalysisCall(
            r.getObject("id", UUID.class), r.getObject("tenant_id", UUID.class),
            r.getObject("project_id", UUID.class), r.getObject("created_by", UUID.class),
            r.getObject("device_id", UUID.class), r.getObject("model_version_id", UUID.class),
            r.getString("key_hash"), r.getString("request_hash"), r.getLong("configuration_revision"),
            AnalysisCall.Status.valueOf(r.getString("status")), instant(r.getTimestamp("created_at")),
            instant(r.getTimestamp("deadline")), instant(r.getTimestamp("expires_at")),
            instant(r.getTimestamp("dispatched_at")), instant(r.getTimestamp("finished_at")));
    @Override public Optional<AnalysisCall> findByKey(UUID tenant, UUID project, UUID creator, String hash) {
        return jdbc.query("SELECT * FROM assistant_analysis_call WHERE tenant_id=? AND project_id=? AND created_by=? AND key_hash=?",
                ROW, tenant, project, creator, hash).stream().findFirst();
    }
    @Override public Optional<AnalysisCall> find(UUID tenant, UUID project, UUID creator, UUID id) {
        return jdbc.query("SELECT * FROM assistant_analysis_call WHERE tenant_id=? AND project_id=? AND created_by=? AND id=?",
                ROW, tenant, project, creator, id).stream().findFirst();
    }
    @Override public boolean insert(AnalysisCall c) {
        return jdbc.update("""
                INSERT INTO assistant_analysis_call(id,tenant_id,project_id,created_by,device_id,model_version_id,
                    key_hash,request_hash,configuration_revision,status,created_at,deadline,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?)
                ON CONFLICT (project_id,created_by,key_hash) DO NOTHING
                """, c.id(), c.tenantId(), c.projectId(), c.createdBy(), c.deviceId(), c.modelVersionId(),
                c.keyHash(), c.requestHash(), c.configurationRevision(), Timestamp.from(c.createdAt()),
                Timestamp.from(c.deadline()), Timestamp.from(c.expiresAt())) == 1;
    }
    @Override public boolean transition(AnalysisCall c, AnalysisCall.Status target, Instant now) {
        return jdbc.update("""
                UPDATE assistant_analysis_call SET status=?,
                    dispatched_at=CASE WHEN ?='DISPATCHED' THEN ? ELSE dispatched_at END,
                    finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','UNKNOWN') THEN CAST(? AS timestamptz) ELSE NULL END
                WHERE tenant_id=? AND project_id=? AND created_by=? AND id=? AND status=?
                """, target.name(), target.name(), Timestamp.from(now), target.name(), Timestamp.from(now),
                c.tenantId(), c.projectId(), c.createdBy(), c.id(), c.status().name()) == 1;
    }
    @Override public int deleteExpired(UUID tenant, UUID project, int limit) {
        return jdbc.update("""
                DELETE FROM assistant_analysis_call WHERE id IN (
                    SELECT id FROM assistant_analysis_call WHERE tenant_id=? AND project_id=?
                    AND expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, tenant, project, Math.max(0, Math.min(limit, 100)));
    }
}
