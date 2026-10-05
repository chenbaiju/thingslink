package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaUploadRepository;
import com.things.link.ota.domain.OtaUploadSession;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** OTA会话持久边界，所有普通写均受调用方事务和完整项目条件保护。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcOtaUploadRepository implements OtaUploadRepository {
    /** 原事务RLS数据入口。 */
    private final JdbcTemplate jdbc;
    /** 固定完整身份谓词，禁止按孤立ID修改跨项目状态。 */
    private static final String SCOPE = " tenant_id=? AND project_id=? AND firmware_id=? AND id=?";
    /** 当前租约能力；过期回执不得重新采用。 */
    private static final String LEASE = " AND lease_token=? AND lease_until>clock_timestamp()";
    /** 白名单投影，无select *泄露到API。 */
    private static final String COLUMNS = "id,tenant_id,project_id,firmware_id,created_by,request_id,project_generation,expected_length,revision,expected_sha256,bucket,object_key,status,version_id,failure_code,key_digest,request_digest,created_at,expires_at,lease_until,write_started_at,write_settled_at,cancel_requested_at,cleanup_completed_at,next_attempt_at,lease_token";
    /** 注入同物理事务的JDBC。 */
    public JdbcOtaUploadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void lockCreation(UUID tenantId,UUID projectId,UUID firmwareId) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
                + "concat_ws(':','ota-upload-create-v1',?::text,?::text,?::text),13017::bigint))",
                Integer.class,tenantId,projectId,firmwareId);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaUploadSession> findCreation(UUID tenantId,UUID projectId,UUID firmwareId,
            UUID accountId,String keyDigest) {
        return query("tenant_id=? AND project_id=? AND firmware_id=? AND created_by=? AND key_digest=?",
                tenantId,projectId,firmwareId,accountId,keyDigest);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaUploadSession> findActive(UUID tenantId,UUID projectId,UUID firmwareId) {
        return query("tenant_id=? AND project_id=? AND firmware_id=? AND status<>'CLEANED'",
                tenantId,projectId,firmwareId);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaUploadSession> find(UUID projectId,UUID firmwareId,UUID id,boolean lock) {
        return query("project_id=? AND firmware_id=? AND id=?"+(lock?" FOR UPDATE":""),projectId,firmwareId,id);
    }
    /** 有界键集查询不使用offset，游标同时绑定项目与固件，禁止跨父资源续页。 */
    @Override public CursorPage<OtaUploadSession> page(UUID projectId,UUID firmwareId,String cursor,int limit) {
        if (limit<1||limit>100) throw invalidCursor();
        List<OtaUploadSession> rows;
        if (cursor==null||cursor.isEmpty()) {
            rows=jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_upload_session WHERE project_id=? AND firmware_id=?"
                    +" ORDER BY created_at DESC,id DESC LIMIT ?",JdbcOtaUploadRepository::map,
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
            rows=jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_upload_session WHERE project_id=? AND firmware_id=?"
                    +" AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                    JdbcOtaUploadRepository::map,projectId,firmwareId,Timestamp.from(time),id,limit+1);
        }
        if (rows.size()<=limit) return CursorPage.last(rows);
        List<OtaUploadSession> items=rows.subList(0,limit);
        OtaUploadSession last=items.getLast();
        return CursorPage.of(items,Cursor.encode(projectId+"|"+firmwareId+"|"+last.createdAt()+"|"+last.id()));
    }
    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER,"上传会话分页参数不合法");
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void create(OtaUploadSession s) {
        jdbc.update("INSERT INTO ota_firmware_upload_session ("+COLUMNS+") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.id(),
                s.tenantId(),
                s.projectId(),
                s.firmwareId(),
                s.createdBy(),
                s.requestId(),
                s.projectGeneration(),
                s.expectedLength(),
                s.revision(),
                s.expectedSha256(),
                s.bucket(),
                s.objectKey(),
                s.status(),
                s.versionId(),
                s.failureCode(),
                s.keyDigest(),
                s.requestDigest(),
                timestamp(s.createdAt()),
                timestamp(s.expiresAt()),
                timestamp(s.leaseUntil()),
                timestamp(s.writeStartedAt()),
                timestamp(s.writeSettledAt()),
                timestamp(s.cancelRequestedAt()),
                timestamp(s.cleanupCompletedAt()),
                timestamp(s.nextAttemptAt()),
                s.leaseToken());
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean claimReceive(OtaUploadSession s,UUID token) {
        return update(s,"status='RECEIVING',revision=revision+1,lease_token=?,"
                + "lease_until=clock_timestamp()+interval '120 seconds'",
                " AND status='WAITING' AND expires_at>clock_timestamp() AND cancel_requested_at IS NULL",
                new Object[]{token},new Object[]{});
    }
    /** 续租单独提交，完整SQL能力端口不依赖被挂起事务的RLS上下文。 */
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean renew(OtaUploadSession s,UUID token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.ota_upload_renew(?,?,?,?,?)",Boolean.class,
                s.tenantId(),s.projectId(),s.firmwareId(),s.id(),token));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markWriting(OtaUploadSession s,UUID token) {
        return leased(s,token,"status='WRITING',revision=revision+1,write_started_at=greatest(clock_timestamp(),created_at)",
                " AND status='RECEIVING' AND cancel_requested_at IS NULL",new Object[]{});
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean recordVersion(OtaUploadSession s,UUID token,String versionId) {
        return leased(s,token,"version_id=?,write_settled_at=coalesce(write_settled_at,greatest(clock_timestamp(),write_started_at)),revision=revision+1",
                " AND status IN ('WRITING','UNKNOWN','CLEANUP_PENDING') AND (version_id IS NULL OR version_id=?)",
                new Object[]{versionId},versionId);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean finishVerified(OtaUploadSession s,UUID token) {
        return leased(s,token,"status='VERIFIED',revision=revision+1,failure_code=NULL,lease_token=NULL,lease_until=NULL",
                " AND status IN ('WRITING','UNKNOWN') AND version_id IS NOT NULL AND write_settled_at IS NOT NULL"
                + " AND cancel_requested_at IS NULL",new Object[]{});
    }
    /** 明确未发送可收束；已知版本转回收，其他可能写入保留UNKNOWN。 */
    @Override public boolean fail(OtaUploadSession s,UUID token,String reason,boolean mayHaveWritten) {
        String target=mayHaveWritten?"CASE WHEN version_id IS NOT NULL THEN 'CLEANUP_PENDING' ELSE 'UNKNOWN' END":"'CLEANED'";
        return leased(s,token,"status="+target+",revision=revision+1,failure_code=?,lease_token=NULL,lease_until=NULL,"
                + "next_attempt_at=clock_timestamp()+interval '120 seconds'"
                + (mayHaveWritten?"":",cleanup_completed_at=clock_timestamp(),write_settled_at=CASE WHEN write_started_at IS NULL"
                    + " THEN NULL ELSE coalesce(write_settled_at,greatest(clock_timestamp(),write_started_at)) END"),
                " AND status IN ('RECEIVING','WRITING','UNKNOWN','CLEANUP_PENDING')"
                + (mayHaveWritten?"":" AND version_id IS NULL AND status IN ('RECEIVING','WRITING')"),new Object[]{reason});
    }
    /** WAITING无网络事实可直接结束，其他状态仅登记意图。 */
    @Override public boolean requestCancel(OtaUploadSession s,long expectedRevision) {
        return update(s,cancelAssignment()," AND status NOT IN ('CLEANED','ADOPTED') AND revision=?",
                new Object[]{},new Object[]{expectedRevision});
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public int cancelForFirmware(UUID tenantId,UUID projectId,UUID firmwareId) {
        return jdbc.update("UPDATE ota_firmware_upload_session SET "+cancelAssignment()
                + " WHERE tenant_id=? AND project_id=? AND firmware_id=? AND status NOT IN ('CLEANED','ADOPTED') AND cancel_requested_at IS NULL",
                tenantId,projectId,firmwareId);
    }
    /** 调用方独立事务同时写入状态变更审计，不在本层提前提交。 */
    @Override public Optional<OtaUploadSession> claimRecovery() {
        return jdbc.query("SELECT "+COLUMNS+" FROM public.ota_upload_claim_recovery()",
                JdbcOtaUploadRepository::map).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean postpone(OtaUploadSession s,UUID token,String reason) {
        return leased(s,token,"revision=revision+1,failure_code=?,lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+interval '120 seconds'",
                " AND status IN ('UNKNOWN','CLEANUP_PENDING')",new Object[]{reason});
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markCleanup(OtaUploadSession s,UUID token,String reason) {
        return leased(s,token,"status='CLEANUP_PENDING',revision=revision+1,failure_code=?",
                " AND status IN ('WRITING','UNKNOWN','VERIFIED','CLEANUP_PENDING')",new Object[]{reason});
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean finishCleanup(OtaUploadSession s,UUID token) {
        return leased(s,token,"status='CLEANED',revision=revision+1,cleanup_completed_at=clock_timestamp(),"
                + "lease_token=NULL,lease_until=NULL,failure_code=NULL",
                " AND status IN ('UNKNOWN','CLEANUP_PENDING') AND (write_started_at IS NULL OR write_settled_at IS NOT NULL)",
                new Object[]{});
    }
    /** 取消不清除正在执行者租约，让其真实连接收到取消；UNKNOWN仍保留未知写入证据。 */
    private static String cancelAssignment() {
        return "cancel_requested_at=coalesce(cancel_requested_at,clock_timestamp()),revision=revision+1,"
                + "status=CASE WHEN status='WAITING' THEN 'CLEANED' WHEN status IN ('VERIFIED','UNKNOWN')"
                + " THEN 'CLEANUP_PENDING' ELSE status END,"
                + "cleanup_completed_at=CASE WHEN status='WAITING' THEN clock_timestamp() ELSE cleanup_completed_at END,"
                + "next_attempt_at=clock_timestamp()";
    }
    /** 固定条件查询，不接受客户端SQL片段。 */
    private Optional<OtaUploadSession> query(String where,Object... args) {
        return jdbc.query("SELECT "+COLUMNS+" FROM ota_firmware_upload_session WHERE "+where,
                JdbcOtaUploadRepository::map,args).stream().findFirst();
    }
    /** 每个CAS同时验证完整scope和当前真实时钟租约。 */
    private boolean leased(OtaUploadSession s,UUID token,String assignment,String where,Object[] values,Object... tail) {
        Object[] suffix=new Object[tail.length+1]; suffix[0]=token;
        System.arraycopy(tail,0,suffix,1,tail.length);
        return update(s,assignment,LEASE+where,values,suffix);
    }
    /** SQL结构由本类常量确定，动态值全部参数化。 */
    private boolean update(OtaUploadSession s,String assignment,String where,Object[] values,Object[] suffix) {
        var args=new ArrayList<Object>(Arrays.asList(values));
        args.addAll(Arrays.asList(s.tenantId(),s.projectId(),s.firmwareId(),s.id()));
        args.addAll(Arrays.asList(suffix));
        return jdbc.update("UPDATE ota_firmware_upload_session SET "+assignment+" WHERE "+SCOPE+where,args.toArray())==1;
    }
    /** 可空时刻保留SQL NULL语义。 */
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    /** 可空数据库时刻不生成虚假的epoch。 */
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    /** 显式投影持久字段，API另行过滤技术身份。 */
    private static OtaUploadSession map(ResultSet rs,int row) throws SQLException {
        return new OtaUploadSession(
                rs.getObject("id",UUID.class),
                rs.getObject("tenant_id",UUID.class),
                rs.getObject("project_id",UUID.class),
                rs.getObject("firmware_id",UUID.class),
                rs.getObject("created_by",UUID.class),
                rs.getObject("request_id",UUID.class),
                rs.getLong("project_generation"),
                rs.getLong("expected_length"),
                rs.getLong("revision"),
                rs.getString("expected_sha256"),
                rs.getString("bucket"),
                rs.getString("object_key"),
                rs.getString("status"),
                rs.getString("version_id"),
                rs.getString("failure_code"),
                rs.getString("key_digest"),
                rs.getString("request_digest"),
                instant(rs,"created_at"),
                instant(rs,"expires_at"),
                instant(rs,"lease_until"),
                instant(rs,"write_started_at"),
                instant(rs,"write_settled_at"),
                instant(rs,"cancel_requested_at"),
                instant(rs,"cleanup_completed_at"),
                instant(rs,"next_attempt_at"),
                rs.getObject("lease_token",UUID.class));
    }
}
