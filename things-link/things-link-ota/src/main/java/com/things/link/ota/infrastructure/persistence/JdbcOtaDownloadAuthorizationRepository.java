package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.ota.domain.OtaDownloadRequestRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 下载授权数据库适配，所有操作参加调用者短事务，网络永不进入仓储。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaDownloadAuthorizationRepository implements OtaDownloadAuthorizationRepository {
    /** 当前数据平面事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入真实事务连接。 */
    public JdbcOtaDownloadAuthorizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT * FROM ota_download_claim_one()", JdbcOtaDownloadAuthorizationRepository::claim)
                .stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> findCurrent(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_download_authorization_snapshot WHERE id=?",
                JdbcOtaDownloadAuthorizationRepository::claim, id).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID id, UUID token) {
        if (id == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_download_authoritative_claim(?,?)",
                JdbcOtaDownloadAuthorizationRepository::claim, id, token).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Transport> authoritativeTransport(UUID id, UUID token) {
        if (id == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_download_authoritative_transport(?,?)",
                JdbcOtaDownloadAuthorizationRepository::transport, id, token).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> reserveSigning(Claim c, long jobRevision) {
        boolean changed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_reserve_signing(?,?,?,?)",
                Boolean.class, c.authorizationId(), c.leaseToken(), c.revision(), jobRevision));
        return changed ? authoritativeClaim(c.authorizationId(), c.leaseToken()) : Optional.empty();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean seal(Claim c, long jobRevision, String keyVersion, byte[] nonce,
            byte[] ciphertext, String hash, String topic) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_seal(?,?,?,?,?,?,?,?,?)", Boolean.class,
                c.authorizationId(), c.leaseToken(), c.revision(), jobRevision, keyVersion, nonce, ciphertext, hash, topic));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean signingUnknown(Claim c, String reason) { return mutation("ota_download_signing_unknown", c, reason); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Transport> reserveSend(Claim c) {
        var reserved = jdbc.query("SELECT id,reservation_token FROM ota_download_reserve_send(?,?,?)",
                (rs, row) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("reservation_token", UUID.class)},
                c.authorizationId(), c.leaseToken(), c.revision()).stream().findFirst();
        return reserved.flatMap(ids -> authoritativeTransport(ids[0], ids[1]));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deferIneligible(Claim c, String reason) { return mutation("ota_download_defer", c, reason); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean recordObservation(Transport t, String outcome, Integer httpStatus, String errorCode) {
        return jdbc.update("UPDATE ota_download_transport SET outcome=?,http_status=?,error_code=?,observed_at=clock_timestamp()"
                + " WHERE id=? AND reservation_token=? AND authorization_id=? AND tenant_id=? AND project_id=? AND outcome IS NULL",
                outcome, httpStatus, errorCode, t.id(), t.reservationToken(), t.authorizationId(), t.tenantId(), t.projectId()) == 1;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean settleCurrent(Claim c, UUID transportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_settle(?,?,?,?)", Boolean.class,
                c.authorizationId(), c.leaseToken(), c.revision(), transportId));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean recoverExpired(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_recover(?,?,?)", Boolean.class,
                c.authorizationId(), c.leaseToken(), c.revision()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean exhaustDue(Claim c, String reason) { return mutation("ota_download_exhaust", c, reason); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean pauseSecurity(Claim c, String reason) { return mutation("ota_download_pause", c, reason); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Reissue> findReissue(UUID authorizationId) {
        if (authorizationId == null) return Optional.empty();
        return jdbc.query("SELECT id,status,transport_count,revision,response_expires_at,reissue_requested_at"
                + " FROM ota_download_authorization WHERE id=?",
                (rs, row) -> new Reissue(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getInt("transport_count"), rs.getLong("revision"), instant(rs, "response_expires_at"),
                        rs.getTimestamp("reissue_requested_at") != null), authorizationId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean requestReissue(UUID authorizationId, long expectedRevision, long expectedJobRevision) {
        if (authorizationId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_reissue_request(?,?,?)", Boolean.class,
                authorizationId, expectedRevision, expectedJobRevision));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean cancelReissue(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_reissue_cancel(?,?,?)", Boolean.class,
                c.authorizationId(), c.leaseToken(), c.revision()));
    }
    /** 固定内部函数名，不拼接外部SQL片段。 */
    private boolean mutation(String function, Claim c, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + function + "(?,?,?,?)", Boolean.class,
                c.authorizationId(), c.leaseToken(), c.revision(), reason));
    }
    /** 读取数据库权威申请、作业修订和当前能力，不复制可变设备当前值到旧身份。 */
    private static Claim claim(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        var request = new OtaDownloadRequestRepository.Request(id, rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getLong("credential_version"),
                rs.getObject("request_id", UUID.class), rs.getObject("job_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getObject("firmware_id", UUID.class), rs.getInt("attempt_no"), rs.getString("manifest_sha256"),
                rs.getBytes("request_canonical"), rs.getString("request_sha256"), instant(rs,"original_deadline"),
                instant(rs,"broker_received_at"), instant(rs,"request_accepted_at"), rs.getLong("report_revision"), rs.getString("report_hash"));
        return new Claim(id, request, rs.getLong("job_revision"), rs.getLong("revision"), rs.getString("status"),
                rs.getInt("transport_count"), rs.getObject("lease_token", UUID.class), instant(rs,"lease_until"),
                rs.getObject("signing_lease_token", UUID.class), instant(rs,"signing_reserved_at"), instant(rs,"response_expires_at"),
                instant(rs,"slot_retain_until"), rs.getLong("artifact_size"), rs.getObject("active_transport_id", UUID.class),
                rs.getString("key_version"), rs.getBytes("nonce"), rs.getBytes("ciphertext"), rs.getString("plaintext_sha256"), rs.getString("topic"));
    }
    /** 原密文与单次观察能力，不包含地址明文。 */
    private static Transport transport(ResultSet rs, int row) throws SQLException {
        return new Transport(rs.getObject("id", UUID.class), rs.getObject("authorization_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getInt("transport_no"), rs.getObject("reservation_token", UUID.class),
                rs.getObject("authorization_lease_token", UUID.class), instant(rs,"response_expires_at"), instant(rs,"reserved_at"),
                rs.getString("key_version"), rs.getBytes("nonce"), rs.getBytes("ciphertext"), rs.getString("plaintext_sha256"), rs.getString("topic"));
    }
    /** 可空数据库时钟字段保持空值语义。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
