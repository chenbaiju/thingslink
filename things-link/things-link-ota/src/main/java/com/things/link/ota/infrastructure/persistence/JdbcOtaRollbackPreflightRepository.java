package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaRollbackPreflightRepository;
import com.things.link.ota.domain.OtaRollbackPreflightQuery;
import com.things.link.ota.domain.OtaRollbackPreflightReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import com.things.link.ota.domain.OtaRollbackPreflightResult;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原恢复责任的独立确定提交证据，所有操作位于真实作用域事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaRollbackPreflightRepository implements OtaRollbackPreflightRepository {
    /** 当前事务数据库连接。 */
    private final JdbcTemplate jdbc;
    /** 注入当前数据平面连接。 */
    public JdbcOtaRollbackPreflightRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaJobProgressRepository.Context> nextCandidate() {
        return jdbc.query("SELECT * FROM ota_rollback_preflight_next_candidate()", (r,row) ->
                new OtaJobProgressRepository.Context(id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                        id(r,"firmware_id"),id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),
                        r.getString("status"),r.getLong("state_version"),r.getLong("qualified_credential_version"),
                        r.getString("manifest_sha256"),id(r,"authorization_id"),at(r,"deadline_at")))
                .stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean deferCandidate(OtaJobProgressRepository.Context c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_defer_candidate(?,?)",
                Boolean.class,c.jobId(),c.revision()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean expireQuery(UUID queryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_expire(?)",Boolean.class,queryId));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean createQuery(OtaJobProgressRepository.Context c, OtaRollbackPreflightQuery q) {
        if (!c.jobId().equals(q.jobId()) || c.revision() != q.recoveryRevision()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_create("
                + "ROW(?,?,?,?,?,?,?,?,?,?,?::uuid[],?,?,?,?,?,?,?,?,?)::ota_rollback_preflight_query)", Boolean.class,
                q.id(),q.tenantId(),q.projectId(),q.campaignId(),q.jobId(),q.deviceId(),q.attemptNo(),
                q.credentialVersion(),q.recoveryRevision(),q.authorizationId(),
                "{"+q.permitIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}",
                q.sourceSlot(),q.targetSlot(),q.manifestSha256(),q.canonical(),q.payloadHash(),
                q.baselineCanonical(),q.typeBaselineCanonical(),time(q.createdAt()),time(q.deadlineAt())));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaRollbackPreflightQuery> findQuery(UUID id) {
        return jdbc.query("SELECT * FROM ota_rollback_preflight_query WHERE id=?",JdbcOtaRollbackPreflightRepository::query,id)
                .stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaRollbackPreflightQuery> currentQuery(UUID job,int attempt) {
        return jdbc.query("SELECT q.* FROM ota_rollback_preflight_control c JOIN ota_rollback_preflight_query q"
                + " ON q.id=c.current_query_id WHERE c.job_id=? AND c.attempt_no=?",
                JdbcOtaRollbackPreflightRepository::query,job,attempt).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaRollbackPreflightResult> findReport(UUID device,UUID report) {
        return jdbc.query("SELECT * FROM ota_rollback_preflight_report WHERE device_id=? AND report_id=?",
                JdbcOtaRollbackPreflightRepository::receipt,device,report).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaRollbackPreflightResult> findReportForQuery(UUID queryId) {
        return jdbc.query("SELECT * FROM ota_rollback_preflight_report WHERE query_id=?",
                JdbcOtaRollbackPreflightRepository::receipt,queryId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean hasSendReservation(UUID query) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ota_rollback_preflight_transport WHERE event_id=?)",
                Boolean.class,query));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean adoptionAllowed(OtaJobProgressRepository.Context c,UUID query,Instant brokerAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_adoption_allowed(?,?,?)",
                Boolean.class,query,c.revision(),time(brokerAt)));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean acceptReport(OtaJobProgressRepository.Context c,OtaRollbackPreflightReceipt r,
            String disposition,String reason,byte[] qualificationCanonical) {
        if (!c.jobId().equals(r.jobId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_rollback_preflight_accept("
                + "ROW(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)::ota_rollback_preflight_report,?,?,?,?)",Boolean.class,
                r.id(),r.tenantId(),r.projectId(),r.campaignId(),r.jobId(),r.deviceId(),r.attemptNo(),r.credentialVersion(),
                r.queryId(),r.reportId(),r.bootId(),r.committedSecurityVersion(),r.canonical(),r.payloadHash(),
                time(r.brokerReceivedAt()),time(r.acceptedAt()),c.revision(),disposition,reason,qualificationCanonical));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaRollbackPreflightResult> latestReport(UUID job,int attempt) {
        return jdbc.query("SELECT r.* FROM ota_rollback_preflight_report r JOIN ota_rollback_preflight_control c"
                + " ON c.current_query_id=r.query_id WHERE r.job_id=? AND r.attempt_no=?",
                JdbcOtaRollbackPreflightRepository::receipt,job,attempt).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public OptionalLong maxObservedCommitted(UUID job,int attempt) {
        Long value=jdbc.queryForObject("SELECT max(committed_security_version) FROM ota_rollback_preflight_report"
                + " WHERE job_id=? AND attempt_no=?",Long.class,job,attempt);
        return value==null?OptionalLong.empty():OptionalLong.of(value);
    }
    /** 不可变查询完整投影。 */
    static OtaRollbackPreflightQuery query(ResultSet r,int row) throws SQLException {
        return new OtaRollbackPreflightQuery(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),
                r.getLong("recovery_revision"),id(r,"authorization_id"),ids(r,"permit_ids"),r.getString("source_slot"),
                r.getString("target_slot"),r.getString("manifest_sha256"),r.getBytes("canonical"),r.getString("payload_hash"),
                r.getBytes("baseline_canonical"),r.getBytes("type_baseline_canonical"),at(r,"created_at"),at(r,"deadline_at"));
    }
    /** 保留原回执及时间，不从最新状态回填历史。 */
    private static OtaRollbackPreflightResult receipt(ResultSet r,int row) throws SQLException {
        var receipt=new OtaRollbackPreflightReceipt(id(r,"id"),id(r,"tenant_id"),id(r,"project_id"),id(r,"campaign_id"),
                id(r,"job_id"),id(r,"device_id"),r.getInt("attempt_no"),r.getLong("credential_version"),
                id(r,"query_id"),id(r,"report_id"),id(r,"boot_id"),r.getLong("committed_security_version"),
                r.getBytes("canonical"),r.getString("payload_hash"),at(r,"broker_received_at"),at(r,"accepted_at"));
        return new OtaRollbackPreflightResult(receipt,r.getString("disposition"),r.getString("reason"),r.getBytes("qualification_canonical"));
    }
    /** 数据库数组复制后释放驱动资源。 */
    private static java.util.List<UUID> ids(ResultSet r,String column) throws SQLException {
        var array=r.getArray(column);
        try { return java.util.List.copyOf(java.util.Arrays.asList((UUID[])array.getArray())); }
        finally { array.free(); }
    }
    /** 精确数据库标识。 */
    private static UUID id(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    /** 保留数据库时间精度。 */
    private static Instant at(ResultSet r,String column) throws SQLException { return r.getTimestamp(column).toInstant(); }
    /** 实际数据库时间参数。 */
    private static Timestamp time(Instant at) { return Timestamp.from(at); }
}
