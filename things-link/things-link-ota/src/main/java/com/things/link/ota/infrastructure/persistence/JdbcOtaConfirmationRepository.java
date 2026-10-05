package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaHealthReceipt;
import com.things.link.ota.domain.OtaCommitPermit;
import com.things.link.ota.domain.OtaCommitReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 健康与成功确认真实持久仓储，不把许可发送回执当作设备成功。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaConfirmationRepository implements OtaConfirmationRepository {
    /** 当前事务数据平面。 */
    private final JdbcTemplate jdbc;
    /** 注入实际事务连接。 */
    public JdbcOtaConfirmationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<UUID> candidateBoot(UUID jobId, int attemptNo) {
        return jdbc.query("SELECT boot_id FROM ota_job_progress WHERE job_id=? AND attempt_no=?"
                + " AND adopted_status='HEALTH_CHECKING'", (rs,row)->rs.getObject(1,UUID.class),jobId,attemptNo)
                .stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaHealthReceipt> findHealth(UUID jobId,int attemptNo,long seq) {
        return jdbc.query("SELECT * FROM ota_health_receipt WHERE job_id=? AND attempt_no=? AND health_seq=?",
                JdbcOtaConfirmationRepository::health,jobId,attemptNo,seq).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaHealthReceipt> firstHealth(UUID jobId,int attemptNo) {
        return healthOrder(jobId,attemptNo,"ASC");
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaHealthReceipt> latestHealth(UUID jobId,int attemptNo) {
        return healthOrder(jobId,attemptNo,"DESC");
    }
    /** 排序方向仅由固定内部常量提供。 */
    private Optional<OtaHealthReceipt> healthOrder(UUID job,int attempt,String direction) {
        return jdbc.query("SELECT * FROM ota_health_receipt WHERE job_id=? AND attempt_no=? ORDER BY health_seq "
                +direction+" LIMIT 1",JdbcOtaConfirmationRepository::health,job,attempt).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean acceptHealth(OtaJobProgressRepository.Context c,OtaHealthReceipt r,
            String next,String reason,OtaCommitPermit permit) {
        if (!c.jobId().equals(r.jobId())) return false;
        var args=new ArrayList<Object>(Arrays.asList(r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.jobId(),r.deviceId(),
                r.attemptNo(),r.credentialVersion(),r.healthSeq(),r.authorizationId(),r.manifestSha256(),r.bootId(),
                r.canonical(),r.payloadHash(),time(r.brokerReceivedAt()),time(r.acceptedAt()),c.revision(),next,reason));
        String permitRow="NULL::ota_commit_permit";
        if(permit!=null) {
            permitRow="ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)::ota_commit_permit";
            args.addAll(Arrays.asList(permit.id(),permit.tenantId(),permit.projectId(),permit.campaignId(),permit.jobId(),permit.deviceId(),
                    permit.attemptNo(),permit.credentialVersion(),permit.authorizationId(),permit.manifestSha256(),permit.bootId(),
                    permit.healthReceiptId(),permit.canonical(),permit.payloadHash(),time(permit.createdAt()),time(permit.deadlineAt())));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_confirmation_accept_health("
                +"ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)::ota_health_receipt,?,?,?,"+permitRow+")",
                Boolean.class,args.toArray()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaCommitPermit> findPermit(UUID job,int attempt) {
        return jdbc.query("SELECT * FROM ota_commit_permit WHERE job_id=? AND attempt_no=?",
                JdbcOtaConfirmationRepository::permit,job,attempt).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean hasSendReservation(UUID permitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ota_commit_transport WHERE event_id=?)",
                Boolean.class,permitId));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaCommitReceipt> findCommitReceipt(UUID device,UUID receiptId) {
        return jdbc.query("SELECT * FROM ota_commit_receipt WHERE device_id=? AND receipt_id=?",
                JdbcOtaConfirmationRepository::commit,device,receiptId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean acceptCommit(OtaJobProgressRepository.Context c,OtaCommitReceipt r,String next,String reason,UUID deviceReceiptId) {
        if(!c.jobId().equals(r.jobId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_confirmation_accept_commit("
                +"ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)::ota_commit_receipt,?,?,?,?)",Boolean.class,
                r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.jobId(),r.deviceId(),r.attemptNo(),r.credentialVersion(),
                r.receiptId(),r.permitId(),r.manifestSha256(),r.bootId(),r.committedSecurityVersion(),r.canonical(),r.payloadHash(),
                time(r.brokerReceivedAt()),time(r.acceptedAt()),c.revision(),next,reason,deviceReceiptId));
    }
    /** 原健康完整字节。 */
    private static OtaHealthReceipt health(ResultSet r,int row) throws SQLException {
        return new OtaHealthReceipt(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"job_id"),id(r,"device_id"),
                r.getInt("attempt_no"),r.getLong("credential_version"),r.getLong("health_seq"),id(r,"authorization_id"),
                r.getString("manifest_sha256"),id(r,"boot_id"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"broker_received_at"),at(r,"accepted_at"));
    }
    /** 不可变许可完整投影，可供同包交付仓储使用。 */
    static OtaCommitPermit permit(ResultSet r,int row) throws SQLException {
        return new OtaCommitPermit(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"job_id"),id(r,"device_id"),
                r.getInt("attempt_no"),r.getLong("credential_version"),id(r,"authorization_id"),r.getString("manifest_sha256"),id(r,"boot_id"),
                id(r,"health_receipt_id"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 原设备提交观察，不推测绑定成功。 */
    private static OtaCommitReceipt commit(ResultSet r,int row) throws SQLException {
        return new OtaCommitReceipt(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"job_id"),id(r,"device_id"),
                r.getInt("attempt_no"),r.getLong("credential_version"),id(r,"receipt_id"),id(r,"permit_id"),r.getString("manifest_sha256"),
                id(r,"boot_id"),r.getLong("committed_security_version"),r.getBytes("canonical"),r.getString("payload_hash"),
                at(r,"broker_received_at"),at(r,"accepted_at"));
    }
    /** 原标识字段。 */
    private static UUID id(ResultSet r,String key) throws SQLException { return r.getObject(key,UUID.class); }
    /** 数据库时间，避免毫秒降精度。 */
    private static Instant at(ResultSet r,String key) throws SQLException { return r.getTimestamp(key).toInstant(); }
    /** 输入真实数据库时间。 */
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
}
