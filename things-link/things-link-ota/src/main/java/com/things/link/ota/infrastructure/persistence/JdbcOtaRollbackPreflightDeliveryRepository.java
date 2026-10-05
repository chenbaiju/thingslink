package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaRollbackPreflightQuery;
import com.things.link.ota.domain.OtaRollbackPreflightDeliveryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定只读恢复查询独立交付预算，网络在调用方事务之外执行。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaRollbackPreflightDeliveryRepository implements OtaRollbackPreflightDeliveryRepository {
    /** 原查询全部字段来自受限查询，不用调用方覆盖范围。 */
    private static final String PROJECTION = """
            (s.query_snapshot->>'id')::uuid AS p_id,
            (s.query_snapshot->>'tenant_id')::uuid AS p_tenant_id,
            (s.query_snapshot->>'project_id')::uuid AS p_project_id,
            (s.query_snapshot->>'campaign_id')::uuid AS p_campaign_id,
            (s.query_snapshot->>'job_id')::uuid AS p_job_id,
            (s.query_snapshot->>'device_id')::uuid AS p_device_id,
            (s.query_snapshot->>'attempt_no')::integer AS p_attempt_no,
            (s.query_snapshot->>'credential_version')::bigint AS p_credential_version,
            (s.query_snapshot->>'recovery_revision')::bigint AS p_recovery_revision,
            (s.query_snapshot->>'authorization_id')::uuid AS p_authorization_id,
            ARRAY(SELECT value::uuid FROM jsonb_array_elements_text(s.query_snapshot->'permit_ids')) AS p_permit_ids,
            (s.query_snapshot->>'source_slot')::text AS p_source_slot,
            (s.query_snapshot->>'target_slot')::text AS p_target_slot,
            (s.query_snapshot->>'manifest_sha256')::text AS p_manifest_sha256,
            decode(substring(s.query_snapshot->>'canonical' FROM 3),'hex') AS p_canonical,
            (s.query_snapshot->>'payload_hash')::text AS p_payload_hash,
            decode(substring(s.query_snapshot->>'baseline_canonical' FROM 3),'hex') AS p_baseline_canonical,
            decode(substring(s.query_snapshot->>'type_baseline_canonical' FROM 3),'hex') AS p_type_baseline_canonical,
            (s.query_snapshot->>'created_at')::timestamptz AS p_created_at,
            (s.query_snapshot->>'deadline_at')::timestamptz AS p_deadline_at
            """;
    /** 实际事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入数据平面。 */
    public JdbcOtaRollbackPreflightDeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_rollback_preflight_claim_one() s",JdbcOtaRollbackPreflightDeliveryRepository::claim).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID permitId,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_rollback_preflight_authoritative_claim(?,?) s",
                JdbcOtaRollbackPreflightDeliveryRepository::claim,permitId,token).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Transport> authoritativeTransport(UUID id,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_rollback_preflight_authoritative_transport(?,?) s",
                (r,row)->transport(r,query(r)),id,token).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Transport> reserveSend(Claim c,String topic) {
        return jdbc.query("SELECT * FROM ota_rollback_preflight_reserve(?,?,?,?,?)",(r,row)->new Transport(id(r,"id"),c.query(),
                r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),topic),
                c.query().id(),c.leaseToken(),c.revision(),topic,c.query().canonical()).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deferIneligible(Claim c,String reason) {
        return jdbc.update("""
                UPDATE ota_rollback_preflight_delivery SET status='RETRY_WAIT',revision=revision+1,
                    next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=greatest(clock_timestamp(),updated_at),
                    reason=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND lease_token=? AND revision=?
                    AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp() AND status IN ('WAITING','RETRY_WAIT')
                """,reason,c.query().id(),c.leaseToken(),c.revision())==1;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean recordObservation(Transport t,String outcome,Integer httpStatus,String errorCode) {
        return jdbc.update("UPDATE ota_rollback_preflight_transport SET outcome=?,http_status=?,error_code=?,observed_at=clock_timestamp()"
                +" WHERE id=? AND reservation_token=? AND tenant_id=? AND project_id=? AND event_id=? AND outcome IS NULL",
                outcome,httpStatus,errorCode,t.id(),t.reservationToken(),t.query().tenantId(),t.query().projectId(),t.query().id())==1;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean settleCurrent(Claim c,UUID transportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_settle(?,?,?,?)",Boolean.class,c.query().id(),c.leaseToken(),c.revision(),transportId));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean recoverExpired(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_recover(?,?,?)",Boolean.class,c.query().id(),c.leaseToken(),c.revision()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean exhaustDue(Claim c,String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_exhaust(?,?,?,?)",Boolean.class,c.query().id(),c.leaseToken(),c.revision(),reason));
    }
    /** 可信范围及当前交付能力。 */
    private static Claim claim(ResultSet r,int row) throws SQLException {
        return new Claim(query(r),r.getLong("revision"),r.getString("status"),r.getInt("transport_count"),
                id(r,"lease_token"),at(r,"lease_until"),id(r,"active_transport_id"),r.getString("topic"));
    }
    /** 观察只携原预留能力。 */
    private static Transport transport(ResultSet r,OtaRollbackPreflightQuery query) throws SQLException {
        return new Transport(id(r,"id"),query,r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),r.getString("topic"));
    }
    /** 固定查询字段防御复制由domain承担。 */
    private static OtaRollbackPreflightQuery query(ResultSet r) throws SQLException {
        return new OtaRollbackPreflightQuery(id(r,"p_id"),id(r,"p_tenant_id"),id(r,"p_project_id"),id(r,"p_campaign_id"),
                id(r,"p_job_id"),id(r,"p_device_id"),r.getInt("p_attempt_no"),r.getLong("p_credential_version"),
                r.getLong("p_recovery_revision"),id(r,"p_authorization_id"),ids(r,"p_permit_ids"),r.getString("p_source_slot"),
                r.getString("p_target_slot"),r.getString("p_manifest_sha256"),r.getBytes("p_canonical"),r.getString("p_payload_hash"),
                r.getBytes("p_baseline_canonical"),r.getBytes("p_type_baseline_canonical"),at(r,"p_created_at"),at(r,"p_deadline_at"));
    }
    /** 数据库数组复制后释放驱动资源。 */
    private static java.util.List<UUID> ids(ResultSet r,String column) throws SQLException {
        var array=r.getArray(column);
        try { return java.util.List.copyOf(java.util.Arrays.asList((UUID[])array.getArray())); }
        finally { array.free(); }
    }
    /** 标识读取。 */
    private static UUID id(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    /** 可空数据库时间读取。 */
    private static Instant at(ResultSet r,String column) throws SQLException {
        var t=r.getTimestamp(column);return t==null?null:t.toInstant();
    }
}
