package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 设备本域提交采用，锁序为类型共享锁、设备排他锁；不获取凭据行锁。 */
@Repository
public class JdbcOtaDeviceCommitAdapter implements OtaDeviceCommitPort {
    /** 调用者已有事务和RLS连接。 */
    private final JdbcTemplate jdbc;
    /** 注入本域数据库入口。 */
    public JdbcOtaDeviceCommitAdapter(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    /** 验证当前物理身份后，单事务记录提交下限，并独立裁决来源CAS。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(Command command) {
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
        var receipts = jdbc.query("SELECT * FROM dev_ota_commit WHERE tenant_id=? AND project_id=? AND device_id=? AND transition_key=?",
                (rs, row) -> mapReceipt(rs), a.tenantId(), a.projectId(), a.deviceId(), command.transitionKey());
        if (!receipts.isEmpty()) {
            var old = receipts.getFirst();
            if (!command.equals(old.command())) return rejected(Decision.SECURITY_CONFLICT);
            var result = old.result();
            return new Result(result.decision(), result.receiptId(), result.bindingTransitionId(), result.recordedAt(),
                    currentFloor(a.tenantId(), a.projectId(), a.deviceId()).orElseThrow(), true);
        }
        var floor = currentFloor(a.tenantId(), a.projectId(), a.deviceId()).orElse(null);
        if (command.committedSecurityVersion() < command.sourceCommittedSecurityVersion()
                || floor != null && (command.committedSecurityVersion() < floor.committedSecurityVersion()
                || command.committedSecurityVersion() == floor.committedSecurityVersion()
                && !command.artifactSha256().equals(floor.artifactSha256()))) return rejected(Decision.SECURITY_CONFLICT);
        boolean sourceMatches = actualType.equals(command.deviceTypeId()) && !typeStates.isEmpty() && typeStates.getFirst()
                && Objects.equals(devices.getFirst(), command.expectedSource().id())
                && modelMatches(a, actualType, command.expectedSource()) && modelMatches(a, actualType, command.target());
        // 同一个转换键若已被另一路模型转换使用，不假称本次完成了那条转换。
        boolean keyUsed = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_device_model_binding_history
                WHERE tenant_id=? AND project_id=? AND device_id=? AND transition_key=?)
                """, Boolean.class, a.tenantId(), a.projectId(), a.deviceId(), command.transitionKey()));
        Decision decision = !command.adoptModel() ? Decision.OBSERVED_ONLY : !sourceMatches || keyUsed ? Decision.SOURCE_CONFLICT
                : command.expectedSource().id().equals(command.target().id()) ? Decision.SAME_MODEL : Decision.CHANGED;
        UUID receiptId = Uuid7.generate();
        UUID transitionId = decision == Decision.CHANGED ? Uuid7.generate() : null;
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        if (!credentialValid(a)) return rejected(Decision.IDENTITY_REJECTED);
        if (transitionId != null) {
            jdbc.update("""
                    INSERT INTO dev_device_model_binding_history
                    (id,tenant_id,project_id,device_id,from_model_version_id,to_model_version_id,
                     transition_key,transition_type,effective_at)
                    VALUES(?,?,?,?,?,?,?,'UPGRADE',?)
                    """, transitionId, a.tenantId(), a.projectId(), a.deviceId(), command.expectedSource().id(),
                    command.target().id(), command.transitionKey(), Timestamp.from(now));
            if (jdbc.update("""
                    UPDATE dev_device SET thing_model_version_id=?,updated_at=?
                    WHERE tenant_id=? AND project_id=? AND id=? AND thing_model_version_id=? AND credential_version=?
                    """, command.target().id(), Timestamp.from(now), a.tenantId(), a.projectId(), a.deviceId(),
                    command.expectedSource().id(), a.credentialVersion()) != 1) throw new IllegalStateException("OTA model CAS lost locked source");
        }
        jdbc.update("""
                INSERT INTO dev_ota_commit(id,tenant_id,project_id,device_id,device_type_id,credential_version,
                transition_key,source_model_id,source_digest_algorithm,source_digest,source_profile,
                target_model_id,target_digest_algorithm,target_digest,target_profile,source_security_version,
                committed_security_version,artifact_sha256,decision,binding_transition_id,recorded_at,adopt_model)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, receiptId, a.tenantId(), a.projectId(), a.deviceId(), command.deviceTypeId(), a.credentialVersion(),
                command.transitionKey(), command.expectedSource().id(), command.expectedSource().digestAlgorithm(),
                command.expectedSource().digest(), command.expectedSource().profile(), command.target().id(),
                command.target().digestAlgorithm(), command.target().digest(), command.target().profile(),
                command.sourceCommittedSecurityVersion(), command.committedSecurityVersion(), command.artifactSha256(),
                decision.name(), transitionId, Timestamp.from(now), command.adoptModel());
        jdbc.update("""
                INSERT INTO dev_ota_security_floor(tenant_id,project_id,device_id,committed_security_version,artifact_sha256,receipt_id)
                VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,project_id,device_id) DO UPDATE
                SET committed_security_version=EXCLUDED.committed_security_version,artifact_sha256=EXCLUDED.artifact_sha256,
                    receipt_id=EXCLUDED.receipt_id
                """, a.tenantId(), a.projectId(), a.deviceId(), command.committedSecurityVersion(), command.artifactSha256(), receiptId);
        return new Result(decision, receiptId, transitionId, now,
                new SecurityFloor(command.committedSecurityVersion(), command.artifactSha256(), receiptId), false);
    }

    /** 同一事务读取真实下限，不把没有证据的存量设备解释为零。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SecurityFloor> currentFloor(UUID tenantId, UUID projectId, UUID deviceId) {
        return jdbc.query("""
                SELECT committed_security_version,artifact_sha256,receipt_id FROM dev_ota_security_floor
                WHERE tenant_id=? AND project_id=? AND device_id=?
                """, (rs, row) -> new SecurityFloor(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class)),
                tenantId, projectId, deviceId).stream().findFirst();
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
        Objects.requireNonNull(c.transitionKey(), "transitionKey");
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
    /** 原始请求与首次决策共同恢复，不能只按目标版本重放。 */
    private static Receipt mapReceipt(ResultSet rs) throws SQLException {
        var command = new Command(new AuthenticatedDeviceIdentity(rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getLong("credential_version")),
                rs.getObject("device_type_id", UUID.class), model(rs, "source"), model(rs, "target"),
                rs.getObject("transition_key", UUID.class), rs.getLong("source_security_version"),
                rs.getLong("committed_security_version"), rs.getString("artifact_sha256"), rs.getBoolean("adopt_model"));
        return new Receipt(command, new Result(Decision.valueOf(rs.getString("decision")), rs.getObject("id", UUID.class),
                rs.getObject("binding_transition_id", UUID.class), rs.getTimestamp("recorded_at").toInstant(), null, false));
    }
    /** 映射明确前缀的冻结模型元组。 */
    private static ModelIdentity model(ResultSet rs, String prefix) throws SQLException {
        return new ModelIdentity(rs.getObject(prefix + "_model_id", UUID.class), rs.getString(prefix + "_digest_algorithm"),
                rs.getString(prefix + "_digest"), rs.getString(prefix + "_profile"));
    }
    /** 本域数据库映射，不向其他模块暴露表结构。 */
    private record Receipt(Command command, Result result) {}
}
