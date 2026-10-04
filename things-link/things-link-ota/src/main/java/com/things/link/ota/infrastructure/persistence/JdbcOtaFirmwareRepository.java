package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只访问OTA自有表，所有查询带项目条件，写入强制加入既有事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaFirmwareRepository implements OtaFirmwareRepository {
    /** 受RLS保护的共享JDBC入口。 */
    private final JdbcTemplate jdbc;
    /** 固件投影列白名单。 */
    private static final String COLUMNS = "id,tenant_id,project_id,created_by,device_type_id,"
            + "thing_model_version_id,product_key,firmware_version,schema_digest_algorithm,"
            + "schema_digest,schema_profile,status,revision,created_at,cancelled_at";
    /** 注入受事务治理的数据库入口。 */
    public JdbcOtaFirmwareRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 锁散列仅用于串行，身份唯一性仍由完整复合主键保证。 */
    @Override
    public void lockCreation(UUID tenantId, UUID projectId, UUID accountId, String keyDigest) {
        jdbc.queryForObject("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(
                    concat_ws(':','ota-firmware-create-v1',?::text,?::text,?::text,?),13014::bigint))
                """, Integer.class, tenantId, projectId, accountId, keyDigest);
    }
    /** 读取当前作用域的精确恢复映射。 */
    @Override
    public Optional<Creation> findCreation(UUID tenantId, UUID projectId, UUID accountId, String keyDigest) {
        return jdbc.query("""
                SELECT firmware_id,request_digest FROM ota_firmware_creation_request
                WHERE tenant_id=? AND project_id=? AND account_id=? AND idempotency_key_digest=?
                """, (rs, row) -> new Creation(rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, projectId, accountId, keyDigest).stream().findFirst();
    }
    /** 固件和恢复身份在同一物理事务内追加。 */
    @Override
    public void create(OtaFirmware f, String keyDigest, String requestDigest) {
        jdbc.update("INSERT INTO ota_firmware (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                f.id(), f.tenantId(), f.projectId(), f.createdBy(), f.deviceTypeId(), f.thingModelVersionId(),
                f.productKey(), f.firmwareVersion(), f.schemaDigestAlgorithm(), f.schemaDigest(), f.schemaProfile(),
                f.status(), f.revision(), Timestamp.from(f.createdAt()), null);
        jdbc.update("""
                INSERT INTO ota_firmware_creation_request
                (tenant_id,project_id,account_id,idempotency_key_digest,request_digest,firmware_id,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, f.tenantId(), f.projectId(), f.createdBy(), keyDigest, requestDigest, f.id(),
                Timestamp.from(f.createdAt()));
    }
    /** 查询单行，可选锁只用于持续写许可保护的事务。 */
    @Override
    public Optional<OtaFirmware> find(UUID projectId, UUID firmwareId, boolean lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ota_firmware WHERE project_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""), JdbcOtaFirmwareRepository::map, projectId, firmwareId)
                .stream().findFirst();
    }
    /** 有界键集查询不会使用offset，也不会扫描其他项目行。 */
    @Override
    public CursorPage<OtaFirmware> page(UUID projectId, String cursor, int limit) {
        if (limit < 1 || limit > 100) { throw invalidCursor(); }
        List<OtaFirmware> rows;
        if (cursor == null || cursor.isEmpty()) {
            rows = jdbc.query("SELECT " + COLUMNS + " FROM ota_firmware WHERE project_id=?"
                    + " ORDER BY created_at DESC,id DESC LIMIT ?", JdbcOtaFirmwareRepository::map,
                    projectId, limit + 1);
        } else {
            Instant time;
            UUID id;
            try {
                if (cursor.length() > 256) { throw invalidCursor(); }
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                if (parts.length != 3 || !projectId.toString().equals(parts[0])) { throw invalidCursor(); }
                time = Instant.parse(parts[1]);
                id = UUID.fromString(parts[2]);
            } catch (RuntimeException exception) { throw invalidCursor(); }
            rows = jdbc.query("SELECT " + COLUMNS + " FROM ota_firmware WHERE project_id=?"
                    + " AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                    JdbcOtaFirmwareRepository::map, projectId, Timestamp.from(time), id, limit + 1);
        }
        if (rows.size() <= limit) { return CursorPage.last(rows); }
        List<OtaFirmware> items = rows.subList(0, limit);
        OtaFirmware last = items.getLast();
        return CursorPage.of(items, Cursor.encode(projectId + "|" + last.createdAt() + "|" + last.id()));
    }
    /** 精确CAS只允许一行从DRAFT转为CANCELLED。 */
    @Override
    public void cancel(UUID projectId, UUID firmwareId, long revision, Instant cancelledAt) {
        int changed = jdbc.update("""
                UPDATE ota_firmware SET status='CANCELLED',revision=revision+1,cancelled_at=?
                WHERE project_id=? AND id=? AND status='DRAFT' AND revision=?
                """, Timestamp.from(cancelledAt), projectId, firmwareId, revision);
        if (changed != 1) { throw new IllegalStateException("固件锁内状态发生漂移"); }
    }
    /** 显式映射持久字段，不把内部身份直接作为HTTP对象暴露。 */
    private static OtaFirmware map(ResultSet rs, int row) throws SQLException {
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new OtaFirmware(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("device_type_id", UUID.class), rs.getObject("thing_model_version_id", UUID.class),
                rs.getString("product_key"), rs.getString("firmware_version"), rs.getString("schema_digest_algorithm"),
                rs.getString("schema_digest"), rs.getString("schema_profile"), rs.getString("status"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }
    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "固件分页参数不合法");
    }
}
