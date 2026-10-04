package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackRepository;
import com.things.link.ota.domain.OtaRollbackOperation;
import com.things.link.ota.domain.OtaRollbackStatusQuery;
import com.things.link.ota.domain.OtaRollbackReport;
import com.things.link.ota.domain.OtaRollbackReportResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原子回退事实仓储，网络不得进入本事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaRollbackRepository implements OtaRollbackRepository {
    /** 当前真实数据平面事务连接。 */
    private final JdbcTemplate jdbc;
    /** 使用实际数据平面连接。 */
    public JdbcOtaRollbackRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public boolean deviceConflicted(UUID deviceId) { return yes("SELECT ota_rollback_device_conflicted(?)",deviceId); }
    /** {@inheritDoc} */
    @Override public Optional<OtaJobProgressRepository.Context> nextCandidate() {
        return jdbc.query("SELECT * FROM ota_rollback_next_candidate()", (r,row) -> new OtaJobProgressRepository.Context(id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"firmware_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getString("status"),r.getLong("state_version"),r.getLong("qualified_credential_version"),r.getString("manifest_sha256"),id(r,"authorization_id"),at(r,"deadline_at"))).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deferCandidate(OtaJobProgressRepository.Context c) {
        return yes("SELECT ota_rollback_defer_candidate(?,?)",c.jobId(),c.revision());
    }
    /** {@inheritDoc} */
    @Override public boolean create(OtaJobProgressRepository.Context c, OtaRollbackOperation o, String reason) {
        if (!c.jobId().equals(o.jobId()) || c.revision()!=o.recoveryRevision()) return false;
        return yes("SELECT ota_rollback_create(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)::ota_rollback_operation,?)",o.id(),o.tenantId(),o.projectId(),o.campaignId(),o.jobId(),o.deviceId(),o.attemptNo(),o.credentialVersion(),o.authorizationId(),o.manifestSha256(),o.preflightQueryId(),o.preflightReceiptId(),o.recoveryRevision(),o.canonical(),o.payloadHash(),time(o.createdAt()),time(o.pendingDeadlineAt()),time(o.rollingDeadlineAt()),reason);
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackOperation> find(UUID id) {
        return jdbc.query("SELECT * FROM ota_rollback_operation WHERE id=?",JdbcOtaRollbackRepository::operation,id).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackOperation> findForJob(UUID job,int attempt) {
        return jdbc.query("SELECT * FROM ota_rollback_operation WHERE job_id=? AND attempt_no=?",JdbcOtaRollbackRepository::operation,job,attempt).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Control> control(UUID id) {
        return jdbc.query("SELECT * FROM ota_rollback_control WHERE operation_id=?",(r,n)->new Control(id(r,"operation_id"),id(r,"accepted_report_id"),id(r,"commit_won_report_id"),at(r,"conflicted_at"),id(r,"current_query_id"),at(r,"next_query_at")),id).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackReportResult> findReport(UUID device,UUID report) {
        return jdbc.query("SELECT * FROM ota_rollback_report WHERE device_id=? AND report_id=?",JdbcOtaRollbackRepository::report,device,report).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackReportResult> findReportForStatusQuery(UUID queryId) {
        return jdbc.query("SELECT * FROM ota_rollback_report WHERE query_id=?",JdbcOtaRollbackRepository::report,queryId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackReportResult> findReportBySequence(UUID operation,long seq) {
        return jdbc.query("SELECT * FROM ota_rollback_report WHERE operation_id=? AND report_seq=?",JdbcOtaRollbackRepository::report,operation,seq).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public long latestSequence(UUID id) {
        return jdbc.queryForObject("SELECT coalesce(max(report_seq),0) FROM ota_rollback_report WHERE operation_id=?",Long.class,id);
    }
    /** {@inheritDoc} */
    @Override public OptionalLong maximumObservedCommitted(UUID id) {
        Long result=jdbc.queryForObject("SELECT max(committed_security_version) FROM ota_rollback_report WHERE operation_id=?",Long.class,id);
        return result==null?OptionalLong.empty():OptionalLong.of(result);
    }
    /** {@inheritDoc} */
    @Override public boolean hasOperationSendReservation(UUID id) {
        return yes("SELECT EXISTS(SELECT 1 FROM ota_rollback_transport WHERE event_id=?)",id);
    }
    /** {@inheritDoc} */
    @Override public boolean acceptReport(OtaJobProgressRepository.Context c,OtaRollbackReport r,String disposition,String reason) {
        if (!c.jobId().equals(r.jobId())) return false;
        return yes("SELECT ota_rollback_accept(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)::ota_rollback_report,?,?,?)",r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.operationId(),r.jobId(),r.deviceId(),r.attemptNo(),r.credentialVersion(),r.queryId(),r.reportId(),r.reportSeq(),r.bootId(),r.status(),r.committedSecurityVersion(),r.canonical(),r.payloadHash(),time(r.brokerReceivedAt()),time(r.acceptedAt()),c.revision(),disposition,reason);
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackOperation> nextStatusCandidate() {
        return jdbc.query("SELECT * FROM ota_rollback_next_status_candidate()",JdbcOtaRollbackRepository::operation).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deferStatusCandidate(UUID id) {
        return yes("SELECT ota_rollback_defer_status_candidate(?)",id);
    }
    /** {@inheritDoc} */
    @Override public boolean createStatusQuery(OtaRollbackStatusQuery q) {
        return yes("SELECT ota_rollback_status_create(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?)::ota_rollback_status_query)",q.id(),q.tenantId(),q.projectId(),q.campaignId(),q.operationId(),q.jobId(),q.deviceId(),q.attemptNo(),q.credentialVersion(),q.canonical(),q.payloadHash(),time(q.createdAt()),time(q.deadlineAt()));
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackStatusQuery> findStatusQuery(UUID id) {
        return jdbc.query("SELECT * FROM ota_rollback_status_query WHERE id=?",JdbcOtaRollbackRepository::query,id).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRollbackStatusQuery> currentStatusQuery(UUID id) {
        return jdbc.query("SELECT q.* FROM ota_rollback_control c JOIN ota_rollback_status_query q ON q.id=c.current_query_id WHERE c.operation_id=?",JdbcOtaRollbackRepository::query,id).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean statusAdoptionAllowed(UUID id,Instant broker) {
        return yes("SELECT ota_rollback_status_adoption_allowed(?,?)",id,time(broker));
    }
    /** {@inheritDoc} */
    @Override public boolean expireStatusQuery(UUID id) {
        return yes("SELECT ota_rollback_status_expire(?)",id);
    }
    /** 真实SQL布尔结果。 */
    private boolean yes(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    /** 不可变原命令。 */
    static OtaRollbackOperation operation(ResultSet r,int row) throws SQLException {
        return new OtaRollbackOperation(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),id(r,"authorization_id"),r.getString("manifest_sha256"),id(r,"preflight_query_id"),id(r,"preflight_receipt_id"),r.getLong("recovery_revision"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"created_at"),at(r,"pending_deadline_at"),at(r,"rolling_deadline_at"));
    }
    /** 不可变只读查询。 */
    private static OtaRollbackStatusQuery query(ResultSet r,int row) throws SQLException {
        return new OtaRollbackStatusQuery(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"operation_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 原观察与固定判断不从现态重写。 */
    private static OtaRollbackReportResult report(ResultSet r,int row) throws SQLException {
        var value=new OtaRollbackReport(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"operation_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),id(r,"query_id"),id(r,"report_id"),r.getLong("report_seq"),id(r,"boot_id"),r.getString("status"),r.getLong("committed_security_version"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"broker_received_at"),at(r,"accepted_at"));
        return new OtaRollbackReportResult(value,r.getString("disposition"),r.getString("reason"));
    }
    /** 原标识。 */
    private static UUID id(ResultSet r,String name) throws SQLException { return r.getObject(name,UUID.class); }
    /** 数据库微秒时间保真。 */
    private static Instant at(ResultSet r,String name) throws SQLException { var value=r.getTimestamp(name);return value==null?null:value.toInstant(); }
    /** 传入原时间。 */
    private static Timestamp time(Instant value) { return value==null?null:Timestamp.from(value); }
}
