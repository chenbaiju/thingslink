package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaReconciliationRepository;
import com.things.link.ota.domain.OtaReconciliationQuery;
import com.things.link.ota.domain.OtaReconciliationReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
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

/** 原恢复责任的独立确定提交证据，所有操作位于真实作用域事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaReconciliationRepository implements OtaReconciliationRepository {
    /** 当前事务数据库连接。 */
    private final JdbcTemplate jdbc;
    /** 注入当前数据平面连接。 */
    public JdbcOtaReconciliationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<OtaJobProgressRepository.Context> nextCandidate() {
        return jdbc.query("SELECT * FROM ota_reconciliation_next_candidate()", (r,row) ->
                new OtaJobProgressRepository.Context(id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                        id(r,"firmware_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),
                        r.getString("status"),r.getLong("state_version"),r.getLong("qualified_credential_version"),
                        r.getString("manifest_sha256"),id(r,"authorization_id"),at(r,"deadline_at")))
                .stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deferCandidate(OtaJobProgressRepository.Context c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_reconciliation_defer_candidate(?,?)",
                Boolean.class,c.jobId(),c.revision()));
    }
    /** {@inheritDoc} */
    @Override public boolean expireQuery(UUID queryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_reconciliation_expire(?)",Boolean.class,queryId));
    }
    /** {@inheritDoc} */
    @Override public boolean createQuery(OtaJobProgressRepository.Context c, OtaReconciliationQuery q) {
        if (!c.jobId().equals(q.jobId()) || c.revision() != q.recoveryRevision()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_reconciliation_create("
                + "ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)::ota_reconciliation_query)", Boolean.class,
                q.id(),q.tenantId(),q.projectId(),q.campaignId(),q.jobId(),q.deviceId(),q.attemptNo(),
                q.credentialVersion(),q.recoveryRevision(),q.authorizationId(),q.permitId(),q.queryNonce(),
                q.commitBootId(),q.manifestSha256(),q.canonical(),q.payloadHash(),time(q.createdAt()),time(q.deadlineAt())));
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaReconciliationQuery> findQuery(UUID id) {
        return jdbc.query("SELECT * FROM ota_reconciliation_query WHERE id=?",JdbcOtaReconciliationRepository::query,id)
                .stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaReconciliationQuery> currentQuery(UUID job,int attempt) {
        return jdbc.query("SELECT q.* FROM ota_reconciliation_control c JOIN ota_reconciliation_query q"
                + " ON q.id=c.current_query_id WHERE c.job_id=? AND c.attempt_no=?",
                JdbcOtaReconciliationRepository::query,job,attempt).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaReconciliationReceipt> findReport(UUID device,UUID report) {
        return jdbc.query("SELECT * FROM ota_reconciliation_report WHERE device_id=? AND report_id=?",
                JdbcOtaReconciliationRepository::receipt,device,report).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaReconciliationReceipt> findReportForQuery(UUID queryId) {
        return jdbc.query("SELECT * FROM ota_reconciliation_report WHERE query_id=?",
                JdbcOtaReconciliationRepository::receipt,queryId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean hasSendReservation(UUID query) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ota_reconciliation_transport WHERE event_id=?)",
                Boolean.class,query));
    }
    /** {@inheritDoc} */
    @Override public boolean adoptionAllowed(OtaJobProgressRepository.Context c,UUID query,Instant brokerAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_reconciliation_adoption_allowed(?,?,?)",
                Boolean.class,query,c.revision(),time(brokerAt)));
    }
    /** {@inheritDoc} */
    @Override public boolean acceptReport(OtaJobProgressRepository.Context c,OtaReconciliationReceipt r,
            boolean succeeded,String reason,UUID deviceReceiptId) {
        if (!c.jobId().equals(r.jobId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_reconciliation_accept("
                + "ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)::ota_reconciliation_report,?,?,?,?)",Boolean.class,
                r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.jobId(),r.deviceId(),r.attemptNo(),r.credentialVersion(),
                r.queryId(),r.reportId(),r.bootId(),r.canonical(),r.payloadHash(),time(r.brokerReceivedAt()),time(r.acceptedAt()),
                c.revision(),succeeded,reason,deviceReceiptId));
    }
    /** 不可变查询完整投影。 */
    static OtaReconciliationQuery query(ResultSet r,int row) throws SQLException {
        return new OtaReconciliationQuery(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),
                r.getLong("recovery_revision"),id(r,"authorization_id"),id(r,"permit_id"),id(r,"query_nonce"),
                id(r,"commit_boot_id"),r.getString("manifest_sha256"),r.getBytes("canonical"),r.getString("payload_hash"),
                at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 保留原回执及时间，不从最新状态回填历史。 */
    private static OtaReconciliationReceipt receipt(ResultSet r,int row) throws SQLException {
        return new OtaReconciliationReceipt(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),
                id(r,"query_id"),id(r,"report_id"),id(r,"boot_id"),r.getBytes("canonical"),
                r.getString("payload_hash"),at(r,"broker_received_at"),at(r,"accepted_at"));
    }
    /** 精确数据库标识。 */
    private static UUID id(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    /** 保留数据库时间精度。 */
    private static Instant at(ResultSet r,String column) throws SQLException { return r.getTimestamp(column).toInstant(); }
    /** 实际数据库时间参数。 */
    private static Timestamp time(Instant at) { return Timestamp.from(at); }
}
