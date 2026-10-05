package com.things.link.assistant.infrastructure.persistence;
import com.things.link.assistant.domain.PersonalEvidenceRecord;
import com.things.link.assistant.domain.PersonalEvidenceRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** 自有记录SQL始终绑定项目真实租户及创建者；列表不读取正文。 */
@Repository
public class JdbcPersonalEvidenceRecordRepository implements PersonalEvidenceRecordRepository {
    private final JdbcTemplate jdbc;
    public JdbcPersonalEvidenceRecordRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String FIELDS = "id,tenant_id,project_id,created_by,device_id,model_version_id,created_at,expires_at,content_sha256";
    private static final RowMapper<PersonalEvidenceRecord> ROW = (r,n) -> new PersonalEvidenceRecord(
            r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),
            r.getObject("created_by",UUID.class),r.getObject("device_id",UUID.class),r.getObject("model_version_id",UUID.class),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("content_sha256"),r.getString("content"));
    @Override public PersonalEvidenceRecord insert(UUID id, UUID tenant, UUID project, UUID creator, UUID device, UUID model, String hash, String content) {
        return jdbc.queryForObject("INSERT INTO assistant_evidence_record(id,tenant_id,project_id,created_by,device_id,model_version_id,content_sha256,content)"
                + " VALUES (?,?,?,?,?,?,?,?) RETURNING " + FIELDS + ",content", ROW, id,tenant,project,creator,device,model,hash,content);
    }
    @Override public List<PersonalEvidenceRecord> list(UUID tenant, UUID project, UUID creator) {
        return jdbc.query("SELECT " + FIELDS + ",NULL::text AS content FROM assistant_evidence_record"
                + " WHERE tenant_id=? AND project_id=? AND created_by=? AND expires_at>clock_timestamp() ORDER BY created_at DESC,id DESC LIMIT 100",
                ROW,tenant,project,creator);
    }
    @Override public Optional<PersonalEvidenceRecord> find(UUID tenant, UUID project, UUID creator, UUID id) {
        return jdbc.query("SELECT " + FIELDS + ",content FROM assistant_evidence_record"
                + " WHERE tenant_id=? AND project_id=? AND created_by=? AND id=? AND expires_at>clock_timestamp()",ROW,tenant,project,creator,id).stream().findFirst();
    }
    @Override public int count(UUID tenant, UUID project, UUID creator) {
        return jdbc.queryForObject("SELECT count(*) FROM assistant_evidence_record WHERE tenant_id=? AND project_id=? AND created_by=? AND expires_at>clock_timestamp()",Integer.class,tenant,project,creator);
    }
    @Override public boolean delete(UUID tenant, UUID project, UUID creator, UUID id) {
        return jdbc.update("DELETE FROM assistant_evidence_record WHERE tenant_id=? AND project_id=? AND created_by=? AND id=?",tenant,project,creator,id)==1;
    }
    @Override public int deleteExpired(UUID tenant, UUID project, UUID creator) {
        return jdbc.update("DELETE FROM assistant_evidence_record WHERE id IN (SELECT id FROM assistant_evidence_record"
                + " WHERE tenant_id=? AND project_id=? AND created_by=? AND expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT 100)",tenant,project,creator);
    }
}
