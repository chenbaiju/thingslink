package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.SelfHostedGrantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自部署授权的共享 PostgreSQL 序号账本；不持有签名密钥或决定业务额度。 */
@Repository
public class JdbcSelfHostedGrantRepository implements SelfHostedGrantRepository {
    private static final RowMapper<StoredGrant> ROW = (result, number) -> new StoredGrant(
            result.getObject("deployment_id", UUID.class),
            result.getObject("billing_tenant_id", UUID.class),
            result.getBytes("deployment_public_key_sha256"),
            result.getObject("grant_id", UUID.class),
            result.getLong("highest_sequence"),
            result.getBytes("signed_envelope"),
            result.getBytes("envelope_sha256"),
            result.getObject("devices_max", Long.class),
            result.getObject("uplink_message_daily", Long.class),
            result.getObject("downlink_message_daily", Long.class));
    private final JdbcTemplate jdbc;

    public JdbcSelfHostedGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockTenantRegistration() {
        jdbc.execute("LOCK TABLE public.sys_tenant IN SHARE ROW EXCLUSIVE MODE");
    }

    @Override
    public List<UUID> allTenantIds() {
        return jdbc.queryForList("SELECT id FROM public.sys_tenant ORDER BY id", UUID.class);
    }

    @Override
    public Optional<StoredGrant> lockCurrent() {
        return find(true);
    }

    @Override
    public Optional<StoredGrant> current() {
        return find(false);
    }

    private Optional<StoredGrant> find(boolean lock) {
        List<StoredGrant> rows = jdbc.query("""
                SELECT deployment_id,billing_tenant_id,deployment_public_key_sha256,grant_id,highest_sequence,
                       signed_envelope,envelope_sha256,devices_max,uplink_message_daily,downlink_message_daily
                  FROM public.sys_shc_local_grant_state WHERE singleton
                """ + (lock ? " FOR UPDATE" : ""), ROW);
        return rows.stream().findFirst();
    }

    @Override
    public void insert(StoredGrant grant) {
        jdbc.update("""
                INSERT INTO public.sys_shc_local_grant_state
                    (singleton,deployment_id,billing_tenant_id,deployment_public_key_sha256,
                     grant_id,highest_sequence,signed_envelope,envelope_sha256,
                     devices_max,uplink_message_daily,downlink_message_daily)
                VALUES (true,?,?,?,?,?,?,?,?,?,?)
                """, grant.deploymentId(), grant.tenantId(), grant.deploymentPublicKeySha256(),
                grant.grantId(), grant.sequence(),
                grant.envelope(), grant.envelopeSha256(),
                grant.devicesMax(), grant.uplinkMessageDaily(), grant.downlinkMessageDaily());
    }

    @Override
    public void replace(StoredGrant grant) {
        int changed = jdbc.update("""
                UPDATE public.sys_shc_local_grant_state
                   SET grant_id=?,highest_sequence=?,signed_envelope=?,envelope_sha256=?,
                       devices_max=?,uplink_message_daily=?,downlink_message_daily=?,
                       imported_at=clock_timestamp()
                 WHERE singleton AND deployment_id=? AND billing_tenant_id=?
                   AND deployment_public_key_sha256=?
                """, grant.grantId(), grant.sequence(), grant.envelope(), grant.envelopeSha256(),
                grant.devicesMax(), grant.uplinkMessageDaily(), grant.downlinkMessageDaily(),
                grant.deploymentId(), grant.tenantId(), grant.deploymentPublicKeySha256());
        if (changed != 1) throw new IllegalStateException("自部署授权持久身份已改变");
    }

    @Override
    public void appendImportAudit(StoredGrant grant) {
        jdbc.update("""
                INSERT INTO public.sys_shc_local_grant_import_audit
                    (grant_id,deployment_id,billing_tenant_id,sequence,envelope_sha256)
                VALUES (?,?,?,?,?)
                """, grant.grantId(), grant.deploymentId(), grant.tenantId(), grant.sequence(),
                grant.envelopeSha256());
    }
}
