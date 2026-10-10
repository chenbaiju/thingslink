package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.application.*;
import com.things.link.enduser.domain.AppPushToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 业务绑定保留RLS；全局占用仅按私钥摘要精确读写，不读取其他租户身份。 */
@Repository
public class JdbcPushInstallationRepository implements PushInstallationRepository {
    private final JdbcTemplate jdbc;
    public JdbcPushInstallationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Optional<UUID> currentGroup(UUID tenant, UUID user, UUID project, UUID sid) {
        return jdbc.query("""
            SELECT session_group_id FROM app_refresh_token WHERE tenant_id=? AND app_user_id=? AND project_id=?
                AND family_id=? AND session_group_id IS NOT NULL AND revoked_at IS NULL AND replaced_by IS NULL
                AND expires_at>clock_timestamp() LIMIT 1
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,user,project,sid).stream().findFirst();
    }
    @Override public Optional<Binding> latest(UUID tenant, UUID user, UUID installation) {
        return jdbc.query("""
            SELECT * FROM app_push_token WHERE tenant_id=? AND app_user_id=? AND installation_id=?
                AND session_group_id IS NOT NULL ORDER BY revision DESC LIMIT 1
            """,(rs,n)->new Binding(rs.getObject("id",UUID.class),installation,rs.getObject("binding_id",UUID.class),
                rs.getObject("session_group_id",UUID.class),rs.getLong("revision"),rs.getObject("registration_id",UUID.class),rs.getString("channel_identity"),rs.getString("channel_configuration_id"),
                rs.getTimestamp("lease_expires_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getString("status")),
                tenant,user,installation).stream().findFirst();
    }
    @Override public void replace(Binding b, AppPushToken t, EncryptedPushToken encrypted, byte[] digest) {
        jdbc.update("""
            INSERT INTO app_push_token (id,tenant_id,app_user_id,installation_id,provider,token_cipher,token_nonce,key_id,
                status,created_at,updated_at,session_group_id,binding_id,revision,channel_configuration_id,token_digest,lease_expires_at,registration_id,channel_identity)
            VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?,?)
            """,t.id(),t.tenantId(),t.appUserId(),t.installationId(),t.provider().name(),encrypted.cipherText(),encrypted.nonce(),encrypted.keyId(),
            Timestamp.from(t.createdAt()),Timestamp.from(t.updatedAt()),b.sessionGroupId(),b.bindingId(),b.revision(),b.channelConfigurationId(),digest,Timestamp.from(b.leaseExpiresAt()),b.registrationId(),b.channelIdentity());
        // 同摘要并发由唯一键串行；后提交者取得唯一资格，旧租户行无需越权更新。
        jdbc.update("INSERT INTO app_push_token_claim(token_digest,push_token_id) VALUES (?,?) "
                +"ON CONFLICT(token_digest) DO UPDATE SET push_token_id=excluded.push_token_id",digest,t.id());
    }
    @Override public void revoke(UUID tenant, UUID user, UUID id, Instant now) {
        jdbc.update("UPDATE app_push_token SET status='REVOKED',revoked_at=?,updated_at=? WHERE tenant_id=? AND app_user_id=? AND id=? AND status='ACTIVE'",
                Timestamp.from(now),Timestamp.from(now),tenant,user,id);
        // 保留摘要占用墓碑；不在换token时反向抢旧摘要锁，避免双安装互换token形成死锁。
    }
    @Override public boolean eligible(UUID tenant, UUID user, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_push_token t WHERE t.tenant_id=? AND t.app_user_id=? AND t.id=? AND "+ELIGIBLE+")",Boolean.class,tenant,user,id));
    }
    /** 只能在已恢复租户范围的短授权事务中使用；旧真实token拒绝，MOCK保留既有确定性测试合同。 */
    static final String ELIGIBLE="""
        t.status='ACTIVE' AND (
            (t.session_group_id IS NULL AND t.provider='MOCK') OR (
                t.session_group_id IS NOT NULL AND t.lease_expires_at>clock_timestamp()
                AND EXISTS(SELECT 1 FROM app_push_token_claim c WHERE c.token_digest=t.token_digest AND c.push_token_id=t.id)
                AND EXISTS(SELECT 1 FROM app_refresh_token s WHERE s.tenant_id=t.tenant_id AND s.app_user_id=t.app_user_id
                    AND s.session_group_id=t.session_group_id AND s.revoked_at IS NULL AND s.replaced_by IS NULL AND s.expires_at>clock_timestamp())
            ))
        """;
}
