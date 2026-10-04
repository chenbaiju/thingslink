package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareLifecycleState;
import com.things.link.ota.domain.OtaFirmwareLifecycleRepository;
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

/** 完整作用域和乐观版本围栏保护的固件退役撤销仓储。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcOtaFirmwareLifecycleRepository implements OtaFirmwareLifecycleRepository {
    /** 固定字段，禁止按API动态拼接投影。 */
    private static final String COLUMNS="id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at,cancelled_at,deprecated_reason,deprecated_by,deprecated_at,revoked_reason,revoked_by,revoked_at";
    /** 同物理事务连接。 */ private final JdbcTemplate jdbc;
    /** 注入调用方事务绑定数据入口。 */
    public JdbcOtaFirmwareLifecycleRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<OtaFirmwareLifecycleState> find(UUID projectId,UUID firmwareId,boolean lock) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware WHERE project_id=? AND id=?"+(lock?" FOR UPDATE":""),
                JdbcOtaFirmwareLifecycleRepository::map,projectId,firmwareId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deprecate(UUID tenantId,UUID projectId,UUID firmwareId,long expectedRevision,
            String reason,UUID actorId,Instant occurredAt) {
        return jdbc.update("UPDATE ota_firmware SET status='DEPRECATED',revision=revision+1,deprecated_reason=?,deprecated_by=?,deprecated_at=? WHERE tenant_id=? AND project_id=? AND id=? AND status='READY' AND revision=?",
                reason,actorId,Timestamp.from(occurredAt),tenantId,projectId,firmwareId,expectedRevision)==1;
    }
    /** {@inheritDoc} */
    @Override public boolean revoke(UUID tenantId,UUID projectId,UUID firmwareId,long expectedRevision,
            String reason,UUID actorId,Instant occurredAt) {
        return jdbc.update("UPDATE ota_firmware SET status='REVOKED',revision=revision+1,revoked_reason=?,revoked_by=?,revoked_at=? WHERE tenant_id=? AND project_id=? AND id=? AND status IN ('READY','DEPRECATED') AND revision=?",
                reason,actorId,Timestamp.from(occurredAt),tenantId,projectId,firmwareId,expectedRevision)==1;
    }
    /** 同一行映射状态与两组可空操作，不补造默认原因或时间。 */
    private static OtaFirmwareLifecycleState map(ResultSet rs,int row) throws SQLException {
        return new OtaFirmwareLifecycleState(firmware(rs,row),transition(rs,"deprecated"),transition(rs,"revoked"));
    }
    /** 三元组空值语义由数据库约束保护。 */
    private static OtaFirmwareLifecycleState.Transition transition(ResultSet rs,String prefix) throws SQLException {
        String reason=rs.getString(prefix+"_reason");
        return reason==null?null:new OtaFirmwareLifecycleState.Transition(reason,rs.getObject(prefix+"_by",UUID.class),
                rs.getTimestamp(prefix+"_at").toInstant());
    }
    /** 显式映射持久字段，不把内部身份直接作为HTTP对象暴露。 */
    private static OtaFirmware firmware(ResultSet rs, int row) throws SQLException {
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new OtaFirmware(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("device_type_id", UUID.class), rs.getObject("thing_model_version_id", UUID.class),
                rs.getString("product_key"), rs.getString("firmware_version"), rs.getString("schema_digest_algorithm"),
                rs.getString("schema_digest"), rs.getString("schema_profile"), rs.getString("status"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }
}
