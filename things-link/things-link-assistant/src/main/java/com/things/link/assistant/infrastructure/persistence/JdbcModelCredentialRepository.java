package com.things.link.assistant.infrastructure.persistence;
import com.things.link.assistant.domain.ModelCredential;
import com.things.link.assistant.domain.ModelCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
/** 项目锁避免首次插入竞争，SQL CAS 防止遗漏版本校验。 */
@Repository
public class JdbcModelCredentialRepository implements ModelCredentialRepository {
    private final JdbcTemplate jdbc;
    public JdbcModelCredentialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<ModelCredential> find(UUID tenant,UUID project) {
        return jdbc.query("SELECT * FROM assistant_model_configuration WHERE tenant_id=? AND project_id=? AND purpose='deepseek-chat'",
            (r,n) -> new ModelCredential(r.getObject("id",UUID.class),tenant,project,r.getLong("revision"),
                r.getLong("credential_revision"),r.getBoolean("enabled"),r.getBytes("ciphertext"),r.getBytes("nonce"),
                r.getString("encryption_key_id"),r.getObject("updated_by",UUID.class),r.getTimestamp("updated_at").toInstant()),
            tenant,project).stream().findFirst();
    }
    @Override public boolean save(ModelCredential c,long expected) {
        if (expected == 0) return jdbc.update("""
            INSERT INTO assistant_model_configuration(id,tenant_id,project_id,purpose,revision,credential_revision,
                enabled,ciphertext,nonce,encryption_key_id,updated_by,updated_at)
            VALUES (?,?,?,'deepseek-chat',?,?,?,?,?,?,?,?) ON CONFLICT (project_id,purpose) DO NOTHING
            """,c.id(),c.tenantId(),c.projectId(),c.revision(),c.credentialRevision(),c.enabled(),c.ciphertext(),
                c.nonce(),c.keyId(),c.updatedBy(),Timestamp.from(c.updatedAt())) == 1;
        return jdbc.update("""
            UPDATE assistant_model_configuration SET revision=?,credential_revision=?,enabled=?,ciphertext=?,
                nonce=?,encryption_key_id=?,updated_by=?,updated_at=?
            WHERE id=? AND tenant_id=? AND project_id=? AND revision=?
            """,c.revision(),c.credentialRevision(),c.enabled(),c.ciphertext(),c.nonce(),c.keyId(),c.updatedBy(),
                Timestamp.from(c.updatedAt()),c.id(),c.tenantId(),c.projectId(),expected) == 1;
    }
}
