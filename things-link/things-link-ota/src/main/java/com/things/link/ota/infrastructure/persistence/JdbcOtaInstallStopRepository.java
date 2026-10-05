package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaInstallStopRepository;
import com.things.link.ota.domain.OtaInstallStopOperation;
import com.things.link.ota.domain.OtaInstallStopStatusQuery;
import com.things.link.ota.domain.OtaInstallStopReport;
import com.things.link.ota.domain.OtaInstallStopReportResult;
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

/** 安装前原子停止事实仓储，网络不得进入本事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaInstallStopRepository implements OtaInstallStopRepository {
    /** 当前真实数据平面事务连接。 */
    private final JdbcTemplate jdbc;
    /** 使用实际数据平面连接。 */
    public JdbcOtaInstallStopRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deviceConflicted(UUID deviceId) { return yes("SELECT ota_install_stop_device_conflicted(?)",deviceId); }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaJobProgressRepository.Context> nextCandidate() {
        return jdbc.query("SELECT * FROM ota_install_stop_next_candidate()", (r,row) -> new OtaJobProgressRepository.Context(id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"firmware_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getString("status"),r.getLong("state_version"),r.getLong("qualified_credential_version"),r.getString("manifest_sha256"),id(r,"authorization_id"),at(r,"deadline_at"))).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deferCandidate(OtaJobProgressRepository.Context c) {
        return yes("SELECT ota_install_stop_defer_candidate(?,?)",c.jobId(),c.revision());
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean create(OtaJobProgressRepository.Context c, OtaInstallStopOperation o, String reason) {
        if (!c.jobId().equals(o.jobId()) || c.revision()!=o.jobRevision()) return false;
        return yes("SELECT ota_install_stop_create(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid[],?,?,?,?)::ota_install_stop_operation,?)",o.id(),o.tenantId(),o.projectId(),o.campaignId(),o.jobId(),o.deviceId(),o.attemptNo(),o.credentialVersion(),o.manifestSha256(),o.originHash(),o.cancellationRevision(),o.jobRevision(),o.parentBaseline(),o.stopBaseline(),uuidArray(o.authorizationIds()),o.canonical(),o.payloadHash(),time(o.createdAt()),time(o.deadlineAt()),reason);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public java.util.List<UUID> authorizationIds(UUID jobId, int attemptNo) {
        // ORDER BY id 必须留在数据库：Postgres的uuid排序与Java UUID.compareTo不同，顺序即元组相等的一部分。
        // D-158：授权表没有attempt_no，按与ota_download_request共享的身份连接并收窄到当前尝试，与DB函数逐字一致。
        return jdbc.queryForList("SELECT a.id FROM ota_download_authorization a JOIN ota_download_request r"
                + " ON r.tenant_id=a.tenant_id AND r.project_id=a.project_id AND r.campaign_id=a.campaign_id"
                + " AND r.job_id=a.job_id AND r.id=a.id WHERE a.job_id=? AND r.attempt_no=? ORDER BY a.id",
                UUID.class,jobId,attemptNo);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopOperation> find(UUID id) {
        return jdbc.query("SELECT * FROM ota_install_stop_operation WHERE id=?",JdbcOtaInstallStopRepository::operation,id).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopOperation> findForJob(UUID job,int attempt) {
        return jdbc.query("SELECT * FROM ota_install_stop_operation WHERE job_id=? AND attempt_no=?",JdbcOtaInstallStopRepository::operation,job,attempt).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Control> control(UUID id) {
        return jdbc.query("SELECT * FROM ota_install_stop_control WHERE operation_id=?",(r,n)->new Control(id(r,"operation_id"),id(r,"accepted_report_id"),id(r,"stopped_report_id"),id(r,"install_won_report_id"),at(r,"conflicted_at"),id(r,"current_query_id"),at(r,"next_query_at"),id(r,"external_evidence_id"),r.getString("external_evidence_kind")),id).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopReportResult> findReport(UUID device,UUID report) {
        return jdbc.query("SELECT * FROM ota_install_stop_report WHERE device_id=? AND report_id=?",JdbcOtaInstallStopRepository::report,device,report).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopReportResult> findReportForStatusQuery(UUID queryId) {
        return jdbc.query("SELECT * FROM ota_install_stop_report WHERE query_id=?",JdbcOtaInstallStopRepository::report,queryId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopReportResult> findReportBySequence(UUID operation,long seq) {
        return jdbc.query("SELECT * FROM ota_install_stop_report WHERE operation_id=? AND report_seq=?",JdbcOtaInstallStopRepository::report,operation,seq).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public long latestSequence(UUID id) {
        return jdbc.queryForObject("SELECT coalesce(max(report_seq),0) FROM ota_install_stop_report WHERE operation_id=?",Long.class,id);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean hasOperationSendReservation(UUID id) {
        return yes("SELECT EXISTS(SELECT 1 FROM ota_install_stop_transport WHERE event_id=?)",id);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean acceptReport(OtaJobProgressRepository.Context c,OtaInstallStopReport r,String disposition,String reason) {
        if (!c.jobId().equals(r.jobId())) return false;
        return yes("SELECT ota_install_stop_accept(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)::ota_install_stop_report,?,?,?)",r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.operationId(),r.jobId(),r.deviceId(),r.attemptNo(),r.credentialVersion(),r.queryId(),r.reportId(),r.reportSeq(),r.bootId(),r.status(),r.canonical(),r.payloadHash(),time(r.brokerReceivedAt()),time(r.acceptedAt()),c.revision(),disposition,reason);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopOperation> nextStatusCandidate() {
        return jdbc.query("SELECT * FROM ota_install_stop_next_status_candidate()",JdbcOtaInstallStopRepository::operation).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deferStatusCandidate(UUID id) {
        return yes("SELECT ota_install_stop_defer_status_candidate(?)",id);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean createStatusQuery(OtaInstallStopStatusQuery q) {
        return yes("SELECT ota_install_stop_status_create(ROW(?,?,?,?,?,?,?,?,?,?,?,?,?)::ota_install_stop_status_query)",q.id(),q.tenantId(),q.projectId(),q.campaignId(),q.operationId(),q.jobId(),q.deviceId(),q.attemptNo(),q.credentialVersion(),q.canonical(),q.payloadHash(),time(q.createdAt()),time(q.deadlineAt()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopStatusQuery> findStatusQuery(UUID id) {
        return jdbc.query("SELECT * FROM ota_install_stop_status_query WHERE id=?",JdbcOtaInstallStopRepository::query,id).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaInstallStopStatusQuery> currentStatusQuery(UUID id) {
        return jdbc.query("SELECT q.* FROM ota_install_stop_control c JOIN ota_install_stop_status_query q ON q.id=c.current_query_id WHERE c.operation_id=?",JdbcOtaInstallStopRepository::query,id).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean statusAdoptionAllowed(UUID id,Instant broker) {
        return yes("SELECT ota_install_stop_status_adoption_allowed(?,?)",id,time(broker));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean expireStatusQuery(UUID id) {
        return yes("SELECT ota_install_stop_status_expire(?)",id);
    }
    /** 真实SQL布尔结果。 */
    private boolean yes(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    /** 不可变原命令。 */
    static OtaInstallStopOperation operation(ResultSet r,int row) throws SQLException {
        return new OtaInstallStopOperation(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),r.getString("manifest_sha256"),r.getString("origin_hash"),r.getLong("cancellation_revision"),r.getLong("job_revision"),r.getBytes("parent_baseline"),r.getBytes("stop_baseline"),java.util.Arrays.asList((UUID[])r.getArray("authorization_ids").getArray()),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 不可变只读查询。 */
    private static OtaInstallStopStatusQuery query(ResultSet r,int row) throws SQLException {
        return new OtaInstallStopStatusQuery(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"operation_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 原观察与固定判断不从现态重写。 */
    private static OtaInstallStopReportResult report(ResultSet r,int row) throws SQLException {
        var value=new OtaInstallStopReport(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),id(r,"operation_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),id(r,"query_id"),id(r,"report_id"),r.getLong("report_seq"),id(r,"boot_id"),r.getString("status"),r.getBytes("canonical"),r.getString("payload_hash"),at(r,"broker_received_at"),at(r,"accepted_at"));
        return new OtaInstallStopReportResult(value,r.getString("disposition"),r.getString("reason"));
    }
    /** UUID由类型化不可变列表序列化，不能插入任意SQL文本。 */
    private static String uuidArray(java.util.List<UUID> ids) { return "{"+ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}"; }
    /** 原标识。 */
    private static UUID id(ResultSet r,String name) throws SQLException { return r.getObject(name,UUID.class); }
    /** 数据库微秒时间保真。 */
    private static Instant at(ResultSet r,String name) throws SQLException { var value=r.getTimestamp(name);return value==null?null:value.toInstant(); }
    /** 传入原时间。 */
    private static Timestamp time(Instant value) { return value==null?null:Timestamp.from(value); }
}
