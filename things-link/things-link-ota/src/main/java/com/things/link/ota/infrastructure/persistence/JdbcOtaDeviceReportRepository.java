package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaDeviceReportState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 同事务报告事实持久，不补查当前凭据来覆盖历史认证代际。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaDeviceReportRepository implements OtaDeviceReportRepository {
    /** 固定投影字段，不接受客户端SQL。 */
    private static final String COLUMNS = "tenant_id,project_id,device_id,credential_version,report_sequence,revision,"
            + "committed_security_version,canonical,report_hash,broker_received_at,accepted_at";
    /** 调用方事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入当前RLS连接。 */
    public JdbcOtaDeviceReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public java.time.Instant currentTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void lockRegistration(UUID tenant, UUID project, UUID device) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
                + "concat_ws(':','ota-device-report-v1',?::text,?::text,?::text),13024::bigint))",
                Integer.class, tenant, project, device);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaDeviceReportState> find(UUID project, UUID device, boolean exclusive, boolean shared) {
        if (exclusive && shared) throw new IllegalArgumentException("报告锁模式互斥");
        return jdbc.query("SELECT " + COLUMNS + ",encode(sha256(canonical),'hex') AS actual_hash"
                + " FROM ota_device_report WHERE project_id=? AND device_id=?"
                + (exclusive ? " FOR UPDATE" : shared ? " FOR SHARE" : ""),
                JdbcOtaDeviceReportRepository::map, project, device).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void create(OtaDeviceReportState s) {
        jdbc.update("INSERT INTO ota_device_report (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                s.tenantId(), s.projectId(), s.deviceId(), s.credentialVersion(), s.reportSequence(), s.revision(),
                s.committedSecurityVersion(), s.canonical(), s.reportHash(), Timestamp.from(s.brokerReceivedAt()),
                Timestamp.from(s.acceptedAt()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean replace(long expectedRevision, OtaDeviceReportState s) {
        return jdbc.update("""
                UPDATE ota_device_report SET credential_version=?,report_sequence=?,revision=?,
                    committed_security_version=?,canonical=?,report_hash=?,broker_received_at=?,accepted_at=?
                WHERE tenant_id=? AND project_id=? AND device_id=? AND revision=?
                """, s.credentialVersion(), s.reportSequence(), s.revision(), s.committedSecurityVersion(),
                s.canonical(), s.reportHash(), Timestamp.from(s.brokerReceivedAt()), Timestamp.from(s.acceptedAt()),
                s.tenantId(), s.projectId(), s.deviceId(), expectedRevision) == 1;
    }
    /** 再核对字节摘要，损坏不能静默变成无报告。 */
    private static OtaDeviceReportState map(ResultSet rs, int row) throws SQLException {
        String hash = rs.getString("report_hash");
        if (hash == null || !hash.equals(rs.getString("actual_hash"))) {
            throw new DataIntegrityViolationException("OTA报告规范摘要损坏");
        }
        return new OtaDeviceReportState(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getLong("credential_version"), rs.getLong("report_sequence"),
                rs.getLong("revision"), rs.getLong("committed_security_version"), rs.getBytes("canonical"), hash,
                rs.getTimestamp("broker_received_at").toInstant(), rs.getTimestamp("accepted_at").toInstant());
    }
}
