package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceCommitPort.ModelIdentity;
import com.things.link.device.application.OtaDeviceReconciliationPort;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立查询对账采用，原提交证据永不改写；锁顺序为类型共享锁、设备排他锁。 */
@Repository
public class JdbcOtaDeviceReconciliationAdapter implements OtaDeviceReconciliationPort {
    /** 同事务普通APP连接。 */ private final JdbcTemplate jdbc;
    /** 原提交事实端口只在缺少原证据时以观察模式调用。 */ private final OtaDeviceCommitPort commits;
    /** 显式保持原端口事务代理，不反射或跨域写表。 */
    public JdbcOtaDeviceReconciliationAdapter(JdbcTemplate jdbc,OtaDeviceCommitPort commits){
        this.jdbc=Objects.requireNonNull(jdbc);this.commits=Objects.requireNonNull(commits);
    }
    /** 对账先保存已发生的提交事实，然后按本次查询独立采用模型。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Result apply(Command command){
        validate(command);
        var a = command.identity();
        var types = jdbc.query("SELECT device_type_id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL",
                (rs, row) -> rs.getObject(1, UUID.class), a.tenantId(), a.projectId(), a.deviceId());
        if (types.isEmpty() || types.getFirst() == null) return rejected(Decision.IDENTITY_REJECTED);
        UUID actualType = types.getFirst();
        var typeStates = jdbc.query("""
                SELECT status='PUBLISHED' AND device_kind='DIRECT' AND deleted_at IS NULL AS usable
                FROM dev_type WHERE tenant_id=? AND project_id=? AND id=? FOR SHARE
                """, (rs, row) -> rs.getBoolean(1), a.tenantId(), a.projectId(), actualType);
        var devices = jdbc.query("""
                SELECT thing_model_version_id FROM dev_device
                WHERE tenant_id=? AND project_id=? AND id=? AND device_type_id=?
                  AND credential_version=? AND deleted_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), a.tenantId(), a.projectId(), a.deviceId(),
                actualType, a.credentialVersion());
        if (devices.isEmpty() || !credentialValid(a)) return rejected(Decision.IDENTITY_REJECTED);

        var originals=jdbc.query("SELECT * FROM dev_ota_commit WHERE tenant_id=? AND project_id=? AND device_id=? AND transition_key=?",
                (rs,row)->original(rs),a.tenantId(),a.projectId(),a.deviceId(),command.originalPermitId());
        UUID originalId;
        if(originals.isEmpty()){
            originalId=null;
        }else{
            var original=originals.getFirst();
            // 原提交采用标志不是新查询采用标志，完整物理元组仍必须逐字段相等。
            if(!original.command().equals(originalCommand(command,original.command().adoptModel())))return rejected(Decision.SECURITY_CONFLICT);
            originalId=original.id();
        }
        var prior=jdbc.query("SELECT * FROM dev_ota_reconciliation WHERE tenant_id=? AND project_id=? AND device_id=? AND reconciliation_id=?",
                (rs,row)->new Stored(rs.getObject("original_commit_receipt_id",UUID.class),rs.getBoolean("adopt_model"),
                    new Result(Decision.valueOf(rs.getString("decision")),rs.getObject("id",UUID.class),
                        rs.getObject("original_commit_receipt_id",UUID.class),rs.getObject("binding_transition_id",UUID.class),
                        rs.getTimestamp("recorded_at").toInstant(),true)),a.tenantId(),a.projectId(),a.deviceId(),command.reconciliationId());
        if(!prior.isEmpty()){
            var replay=prior.getFirst();
            if(!Objects.equals(replay.originalId(),originalId)||replay.adoptModel()!=command.adoptModel())return rejected(Decision.SECURITY_CONFLICT);
            return replay.result();
        }
        var floor=commits.currentFloor(a.tenantId(),a.projectId(),a.deviceId()).orElse(null);
        if(floor!=null&&(floor.committedSecurityVersion()>command.committedSecurityVersion()
                ||originalId!=null&&floor.committedSecurityVersion()!=command.committedSecurityVersion()
                ||floor.committedSecurityVersion()==command.committedSecurityVersion()&&!floor.artifactSha256().equals(command.artifactSha256())))
            return rejected(Decision.SECURITY_CONFLICT);
        boolean sourceMatches=actualType.equals(command.deviceTypeId())&&!typeStates.isEmpty()&&typeStates.getFirst()
                &&Objects.equals(devices.getFirst(),command.expectedSource().id())
                &&modelMatches(a,actualType,command.expectedSource())&&modelMatches(a,actualType,command.target());
        boolean keyUsed=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_device_model_binding_history
                WHERE tenant_id=? AND project_id=? AND device_id=? AND transition_key=?)
                """,Boolean.class,a.tenantId(),a.projectId(),a.deviceId(),command.reconciliationId()));
        Decision decision=!command.adoptModel()?Decision.OBSERVED_ONLY:!sourceMatches||keyUsed?Decision.SOURCE_CONFLICT
                :command.expectedSource().id().equals(command.target().id())?Decision.SAME_MODEL:Decision.CHANGED;
        if(!credentialValid(a))return rejected(Decision.IDENTITY_REJECTED);
        if(originalId==null){
            var recorded=commits.apply(originalCommand(command,false));
            if(recorded.decision()==OtaDeviceCommitPort.Decision.IDENTITY_REJECTED)return rejected(Decision.IDENTITY_REJECTED);
            if(recorded.decision()==OtaDeviceCommitPort.Decision.SECURITY_CONFLICT)return rejected(Decision.SECURITY_CONFLICT);
            originalId=recorded.receiptId();
        }
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        UUID id=Uuid7.generate(),binding=decision==Decision.CHANGED?Uuid7.generate():null;
        if(binding!=null){
            jdbc.update("""
                    INSERT INTO dev_device_model_binding_history(id,tenant_id,project_id,device_id,from_model_version_id,
                    to_model_version_id,transition_key,transition_type,effective_at) VALUES(?,?,?,?,?,?,?,'UPGRADE',?)
                    """,binding,a.tenantId(),a.projectId(),a.deviceId(),command.expectedSource().id(),command.target().id(),
                    command.reconciliationId(),Timestamp.from(now));
            if(jdbc.update("""
                    UPDATE dev_device SET thing_model_version_id=?,updated_at=? WHERE tenant_id=? AND project_id=? AND id=?
                    AND thing_model_version_id=? AND credential_version=? AND deleted_at IS NULL
                    """,command.target().id(),Timestamp.from(now),a.tenantId(),a.projectId(),a.deviceId(),
                    command.expectedSource().id(),a.credentialVersion())!=1)throw new IllegalStateException("OTA reconciliation lost locked source");
        }
        jdbc.update("""
                INSERT INTO dev_ota_reconciliation(id,tenant_id,project_id,device_id,reconciliation_id,original_permit_id,
                original_commit_receipt_id,adopt_model,decision,binding_transition_id,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,id,a.tenantId(),a.projectId(),a.deviceId(),command.reconciliationId(),command.originalPermitId(),
                originalId,command.adoptModel(),decision.name(),binding,Timestamp.from(now));
        return new Result(decision,id,originalId,binding,now,false);
    }
    /** 新查询只复用原物理提交元组，不能把原OBSERVED_ONLY升级为另一原始裁决。 */
    private static OtaDeviceCommitPort.Command originalCommand(Command command,boolean adopt){
        return new OtaDeviceCommitPort.Command(command.identity(),command.deviceTypeId(),command.expectedSource(),command.target(),
                command.originalPermitId(),command.sourceCommittedSecurityVersion(),command.committedSecurityVersion(),command.artifactSha256(),adopt);
    }
    /** 普通MVCC检查凭据自然到期，避免凭据→设备锁顺序反转。 */
    private boolean credentialValid(AuthenticatedDeviceIdentity a) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("""
                SELECT count(*)::int FROM dev_credential WHERE tenant_id=? AND project_id=? AND device_id=?
                AND auth_type='ACCESS_TOKEN' AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at>clock_timestamp())
                """, Integer.class, a.tenantId(), a.projectId(), a.deviceId()));
    }
    /** 模型完整归属和不可变摘要必须与许可元组一致。 */
    private boolean modelMatches(AuthenticatedDeviceIdentity a, UUID type, ModelIdentity model) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_thing_model_version WHERE tenant_id=? AND project_id=?
                AND device_type_id=? AND id=? AND digest_algorithm=? AND schema_digest=? AND schema_profile=?)
                """, Boolean.class, a.tenantId(), a.projectId(), type, model.id(), model.digestAlgorithm(), model.digest(), model.profile()));
    }
    /** 首次失败不泄露另一身份的已知下限。 */
    private static Result rejected(Decision decision) { return new Result(decision, null, null, null, null, false); }
    /** 调用端口的结构错误拒绝在任何数据库操作前。 */
    private static void validate(Command c) {
        Objects.requireNonNull(c, "command");
        Objects.requireNonNull(c.identity(), "identity");
        Objects.requireNonNull(c.deviceTypeId(), "deviceTypeId");
        Objects.requireNonNull(c.originalPermitId(), "originalPermitId");
        Objects.requireNonNull(c.reconciliationId(), "reconciliationId");
        validateModel(c.expectedSource()); validateModel(c.target());
        if (c.sourceCommittedSecurityVersion() < 0 || c.committedSecurityVersion() < 0
                || c.sourceCommittedSecurityVersion() > 9007199254740991L || c.committedSecurityVersion() > 9007199254740991L
                || c.artifactSha256() == null || !c.artifactSha256().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid OTA commit tuple");
    }
    /** 模型元组必须采用项目冻结的摘要及属性解释标准。 */
    private static void validateModel(ModelIdentity model) {
        Objects.requireNonNull(model, "model"); Objects.requireNonNull(model.id(), "model.id");
        if (!"PG_JSONB_TEXT_V1_SHA256".equals(model.digestAlgorithm()) || !"TC_PROPERTY_COMPOSITE_V1".equals(model.profile())
                || model.digest() == null || !model.digest().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid model tuple");
    }

    /** 原提交记录完整元组，采用标记保持原值。 */
    private static Original original(ResultSet rs)throws SQLException{
        var identity=new AuthenticatedDeviceIdentity(rs.getObject("tenant_id",UUID.class),rs.getObject("project_id",UUID.class),
                rs.getObject("device_id",UUID.class),rs.getLong("credential_version"));
        return new Original(rs.getObject("id",UUID.class),new OtaDeviceCommitPort.Command(identity,rs.getObject("device_type_id",UUID.class),
                model(rs,"source"),model(rs,"target"),rs.getObject("transition_key",UUID.class),rs.getLong("source_security_version"),
                rs.getLong("committed_security_version"),rs.getString("artifact_sha256"),rs.getBoolean("adopt_model")));
    }
    /** 映射原提交的冻结模型元组。 */
    private static ModelIdentity model(ResultSet rs,String prefix)throws SQLException{
        return new ModelIdentity(rs.getObject(prefix+"_model_id",UUID.class),rs.getString(prefix+"_digest_algorithm"),
                rs.getString(prefix+"_digest"),rs.getString(prefix+"_profile"));
    }
    /** 原事实通过不可变FK归一化保存，不复制成可漂移的第二套字段。 */
    private record Original(UUID id,OtaDeviceCommitPort.Command command){}
    /** 查询重放保留原采用标志和裁决。 */
    private record Stored(UUID originalId,boolean adoptModel,Result result){}
}
