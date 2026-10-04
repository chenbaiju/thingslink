package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaPublicationRepository;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 强租约发布仓储，所有状态与审计加入调用方同一短事务。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcOtaPublicationRepository implements OtaPublicationRepository {
    /** 固定发布字段投影。 */
    private static final String COLUMNS="id,tenant_id,project_id,firmware_id,upload_session_id,created_by,request_id,project_generation,firmware_revision,upload_revision,canonical_manifest,trust_snapshot,status,revision,spki,signature,receipt,failure_code,lease_token,lease_until,created_at,updated_at";
    /** 不可变发布物固定字段。 */
    private static final String RELEASE_COLUMNS="id,tenant_id,project_id,firmware_id,upload_session_id,publication_id,canonical_manifest,trust_snapshot,spki,signature,receipt,created_at";
    /** 租约精确作用域。 */
    private static final String SCOPE=" tenant_id=? AND project_id=? AND firmware_id=? AND id=? AND lease_token=? AND lease_until>clock_timestamp()";
    /** 同事务数据入口。 */
    private final JdbcTemplate jdbc;
    /** 注入事务绑定JDBC。 */
    public JdbcOtaPublicationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** {@inheritDoc} */
    @Override public void create(OtaPublication s) {
        jdbc.update("INSERT INTO ota_firmware_publication ("+COLUMNS+") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",s.id(),s.tenantId(),s.projectId(),s.firmwareId(),s.uploadSessionId(),s.createdBy(),s.requestId(),s.projectGeneration(),s.firmwareRevision(),s.uploadRevision(),s.canonicalManifest(),s.trustSnapshot(),s.status(),s.revision(),s.spki(),s.signature(),s.receipt(),s.failureCode(),s.leaseToken(),timestamp(s.leaseUntil()),timestamp(s.createdAt()),timestamp(s.updatedAt()));
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaPublication> find(UUID projectId,UUID firmwareId,UUID id,boolean lock) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_publication WHERE project_id=? AND firmware_id=? AND id=?"+(lock?" FOR UPDATE":""),JdbcOtaPublicationRepository::map,projectId,firmwareId,id).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaPublication> findActive(UUID projectId,UUID firmwareId) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_publication WHERE project_id=? AND firmware_id=? AND status<>'REJECTED'",JdbcOtaPublicationRepository::map,projectId,firmwareId).stream().findFirst();
    }
    /** 有界键集查询不使用offset，游标同时绑定项目与固件，禁止跨父资源续页。 */
    @Override public CursorPage<OtaPublication> page(UUID projectId,UUID firmwareId,String cursor,int limit) {
        if (limit<1||limit>100) throw invalidCursor();
        List<OtaPublication> rows;
        if (cursor==null||cursor.isEmpty()) {
            rows=jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_publication WHERE project_id=? AND firmware_id=?"
                    +" ORDER BY created_at DESC,id DESC LIMIT ?",JdbcOtaPublicationRepository::map,
                    projectId,firmwareId,limit+1);
        } else {
            Instant time;
            UUID id;
            try {
                if (cursor.length()>320) throw invalidCursor();
                String[] parts=Cursor.decode(cursor).split("\\|",-1);
                if (parts.length!=4||!projectId.toString().equals(parts[0])
                        ||!firmwareId.toString().equals(parts[1])) throw invalidCursor();
                time=Instant.parse(parts[2]);
                id=UUID.fromString(parts[3]);
            } catch (RuntimeException exception) { throw invalidCursor(); }
            rows=jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_publication WHERE project_id=? AND firmware_id=?"
                    +" AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                    JdbcOtaPublicationRepository::map,projectId,firmwareId,Timestamp.from(time),id,limit+1);
        }
        if (rows.size()<=limit) return CursorPage.last(rows);
        List<OtaPublication> items=rows.subList(0,limit);
        OtaPublication last=items.getLast();
        return CursorPage.of(items,Cursor.encode(projectId+"|"+firmwareId+"|"+last.createdAt()+"|"+last.id()));
    }
    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER,"发布尝试分页参数不合法");
    }
    /** 可信领取只锁尝试，不逆向等待业务父锁。 */
    @Override public Optional<OtaPublication> claimPreparedOrSigned() {
        return jdbc.query("SELECT "+COLUMNS+" FROM public.ota_firmware_publication_claim()",JdbcOtaPublicationRepository::map).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean renew(OtaPublication s,UUID token) {
        return update(s,token,"lease_until=clock_timestamp()+interval '120 seconds'",
            " AND status IN ('SIGNING','SIGNED') AND EXISTS (SELECT 1 FROM sys_project p WHERE p.id=project_id AND p.tenant_id=ota_firmware_publication.tenant_id AND p.status='ACTIVE' AND p.lifecycle_generation=project_generation)");
    }
    /** 当前token而非旧快照revision支配回执写入。 */
    @Override public boolean recordSigned(OtaPublication s,UUID token,byte[] spki,byte[] signature,String receipt) {
        return update(s,token,"status='SIGNED',revision=revision+1,spki=?,signature=?,receipt=?,updated_at=greatest(clock_timestamp(),updated_at)"," AND status='SIGNING'",spki,signature,receipt);
    }
    /** {@inheritDoc} */
    @Override public boolean reject(OtaPublication s,UUID token,String reason) {
        return update(s,token,"status='REJECTED',revision=revision+1,failure_code=?,lease_token=NULL,lease_until=NULL,updated_at=greatest(clock_timestamp(),updated_at)"," AND status IN ('SIGNING','SIGNED')",reason);
    }
    /** {@inheritDoc} */
    @Override public boolean unknown(OtaPublication s,UUID token,String reason) {
        return update(s,token,"status='UNKNOWN',revision=revision+1,failure_code=?,lease_token=NULL,lease_until=NULL,updated_at=greatest(clock_timestamp(),updated_at)"," AND status='SIGNING'",reason);
    }
    /** 前置不匹配不写；首条写入后的任何CAS失败必须抛出以回滚整片事实。 */
    @Override public boolean commitRelease(OtaPublication s,UUID token,OtaRelease r) {
        if (!r.id().equals(s.id()) || !r.publicationId().equals(s.id()) || !r.tenantId().equals(s.tenantId())
                || !r.projectId().equals(s.projectId()) || !r.firmwareId().equals(s.firmwareId())
                || !r.uploadSessionId().equals(s.uploadSessionId())) {
            throw new DataIntegrityViolationException("Release identity differs from publication");
        }
        Boolean eligible=jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ota_firmware_publication a
                JOIN sys_project p ON p.tenant_id=a.tenant_id AND p.id=a.project_id
                JOIN ota_firmware f ON f.tenant_id=a.tenant_id AND f.project_id=a.project_id AND f.id=a.firmware_id
                JOIN ota_firmware_upload_session u ON u.tenant_id=a.tenant_id AND u.project_id=a.project_id
                    AND u.firmware_id=a.firmware_id AND u.id=a.upload_session_id
                WHERE a.tenant_id=? AND a.project_id=? AND a.firmware_id=? AND a.id=?
                    AND a.lease_token=? AND a.lease_until>clock_timestamp() AND a.status='SIGNED'
                    AND p.status='ACTIVE' AND p.lifecycle_generation=a.project_generation
                    AND f.status='DRAFT' AND f.revision=a.firmware_revision AND f.revision=?
                    AND u.status='VERIFIED' AND u.revision=a.upload_revision AND u.revision=?
                    AND u.cancel_requested_at IS NULL)
                """,Boolean.class,s.tenantId(),s.projectId(),s.firmwareId(),s.id(),token,s.firmwareRevision(),s.uploadRevision());
        if (!Boolean.TRUE.equals(eligible)) return false;
        requireOne(jdbc.update("INSERT INTO ota_firmware_release ("+RELEASE_COLUMNS+") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",r.id(),r.tenantId(),r.projectId(),r.firmwareId(),r.uploadSessionId(),r.publicationId(),r.canonicalManifest(),r.trustSnapshot(),r.spki(),r.signature(),r.receipt(),timestamp(r.createdAt())));
        requireOne(jdbc.update("UPDATE ota_firmware SET status='VERIFYING',revision=revision+1 WHERE tenant_id=? AND project_id=? AND id=? AND status='DRAFT' AND revision=?",s.tenantId(),s.projectId(),s.firmwareId(),s.firmwareRevision()));
        requireOne(jdbc.update("UPDATE ota_firmware SET status='READY',revision=revision+1 WHERE tenant_id=? AND project_id=? AND id=? AND status='VERIFYING' AND revision=?",s.tenantId(),s.projectId(),s.firmwareId(),s.firmwareRevision()+1));
        requireOne(jdbc.update("UPDATE ota_firmware_upload_session SET status='ADOPTED',revision=revision+1 WHERE tenant_id=? AND project_id=? AND firmware_id=? AND id=? AND status='VERIFIED' AND revision=? AND cancel_requested_at IS NULL",s.tenantId(),s.projectId(),s.firmwareId(),s.uploadSessionId(),s.uploadRevision()));
        if (!update(s,token,"status='COMMITTED',revision=revision+1,lease_token=NULL,lease_until=NULL,updated_at=greatest(clock_timestamp(),updated_at)"," AND status='SIGNED'")) {
            throw new DataIntegrityViolationException("Publication lease changed during atomic release");
        }
        return true;
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaRelease> findRelease(UUID projectId,UUID firmwareId) {
        return jdbc.query("SELECT "+RELEASE_COLUMNS+" FROM ota_firmware_release WHERE project_id=? AND firmware_id=?",JdbcOtaPublicationRepository::mapRelease,projectId,firmwareId).stream().findFirst();
    }
    /** 参数化固定SQL，完整作用域与真实时钟租约始终同时判断。 */
    private boolean update(OtaPublication s,UUID token,String assignment,String condition,Object... values) {
        var args=new ArrayList<Object>(Arrays.asList(values));
        args.addAll(Arrays.asList(s.tenantId(),s.projectId(),s.firmwareId(),s.id(),token));
        return jdbc.update("UPDATE ota_firmware_publication SET "+assignment+" WHERE "+SCOPE+condition,args.toArray())==1;
    }
    /** 中途失败不得以false提交半成品。 */
    private static void requireOne(int changed) {
        if (changed!=1) throw new DataIntegrityViolationException("Atomic release compare-and-set failed");
    }
    /** 保留空租约语义。 */
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    /** 显式映射可空时刻。 */
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    /** 白名单发布事实。 */
    private static OtaPublication map(ResultSet rs,int row) throws SQLException {
        return new OtaPublication(rs.getObject("id",UUID.class),
                rs.getObject("tenant_id",UUID.class),
                rs.getObject("project_id",UUID.class),
                rs.getObject("firmware_id",UUID.class),
                rs.getObject("upload_session_id",UUID.class),
                rs.getObject("created_by",UUID.class),
                rs.getObject("request_id",UUID.class),
                rs.getLong("project_generation"),
                rs.getLong("firmware_revision"),
                rs.getLong("upload_revision"),
                rs.getBytes("canonical_manifest"),
                rs.getBytes("trust_snapshot"),
                rs.getString("status"),
                rs.getLong("revision"),
                rs.getBytes("spki"),
                rs.getBytes("signature"),
                rs.getString("receipt"),
                rs.getString("failure_code"),
                rs.getObject("lease_token",UUID.class),
                instant(rs,"lease_until"),
                instant(rs,"created_at"),
                instant(rs,"updated_at"));
    }
    /** 白名单不可变发布物。 */
    private static OtaRelease mapRelease(ResultSet rs,int row) throws SQLException {
        return new OtaRelease(rs.getObject("id",UUID.class),
                rs.getObject("tenant_id",UUID.class),
                rs.getObject("project_id",UUID.class),
                rs.getObject("firmware_id",UUID.class),
                rs.getObject("upload_session_id",UUID.class),
                rs.getObject("publication_id",UUID.class),
                rs.getBytes("canonical_manifest"),
                rs.getBytes("trust_snapshot"),
                rs.getBytes("spki"),
                rs.getBytes("signature"),
                rs.getString("receipt"),
                instant(rs,"created_at"));
    }
}
