package com.things.link.ota.application;

import com.things.link.device.application.OtaModelSnapshot;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaUploadRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR0114草稿创建、查询与取消；模型资格不能被外推为上传或签名就绪。 */
@Service
public class OtaFirmwareService {
    /** 本域持久事实及恢复映射。 */
    private final OtaFirmwareRepository repository;
    /** 项目角色和真实归属公开服务。 */
    private final ProjectService projects;
    /** 当前事务内持续ACTIVE写许可。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 已发布类型精确模型公开投影。 */
    private final OtaModelSnapshotPort models;
    /** 同事务取消子上传，物理回收仍由独立worker完成。 */
    private final OtaUploadRepository uploads;
    /** 与固件变更共用事务的审计服务。 */
    private final AuditLogService audit;

    /** 显式注入所有资格与持久化依赖。 */
    public OtaFirmwareService(OtaFirmwareRepository repository, ProjectService projects,
                              ProjectLifecycleAccessService lifecycle, OtaModelSnapshotPort models,
                              AuditLogService audit, OtaUploadRepository uploads) {
        this.repository = Objects.requireNonNull(repository);
        this.projects = Objects.requireNonNull(projects);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.models = Objects.requireNonNull(models);
        this.audit = Objects.requireNonNull(audit);
        this.uploads = Objects.requireNonNull(uploads);
    }

    /** 按四轴摘要身份恢复原始固件，已取消结果不得重建。 */
    @Transactional
    public OtaFirmware createIdempotent(UUID projectId, String idempotencyKey, UUID deviceTypeId,
                                        UUID thingModelVersionId, String firmwareVersion) {
        UUID tenant = requireWrite(projectId);
        requireKey(idempotencyKey);
        // 语法必须先于UTF8摘要；孤立代理码元不能以替换字符碰撞已有恢复身份。
        if (deviceTypeId == null || thingModelVersionId == null) { throw invalid(); }
        requireVersion(firmwareVersion);
        UUID account = TenantContext.require().accountId();
        String keyDigest = digest("tc.ota-firmware-create-key/v1", idempotencyKey);
        String requestDigest = digest("tc.ota-firmware-create-request/v1",
                deviceTypeId == null ? null : deviceTypeId.toString(),
                thingModelVersionId == null ? null : thingModelVersionId.toString(), firmwareVersion);
        repository.lockCreation(tenant, projectId, account, keyDigest);
        OtaFirmwareRepository.Creation existing = repository.findCreation(tenant, projectId, account, keyDigest)
                .orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
            }
            OtaFirmware restored = repository.find(projectId, existing.firmwareId(), true)
                    .orElseThrow(() -> new IllegalStateException("固件恢复映射缺少目标"));
            if (!tenant.equals(restored.tenantId()) || !account.equals(restored.createdBy())) {
                throw new IllegalStateException("固件恢复身份漂移");
            }
            if (!"DRAFT".equals(restored.status())) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            }
            return restored;
        }
        OtaModelSnapshot model = models.find(projectId, deviceTypeId, thingModelVersionId)
                .orElseThrow(() -> new BusinessException(OtaFirmwareErrorCode.MODEL_CONFLICT));
        if (!projectId.equals(model.projectId()) || !deviceTypeId.equals(model.deviceTypeId())
                || !thingModelVersionId.equals(model.thingModelVersionId()) || model.productKey() == null
                || !model.productKey().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || !"PG_JSONB_TEXT_V1_SHA256".equals(model.schemaDigestAlgorithm())
                || !"TC_PROPERTY_COMPOSITE_V1".equals(model.schemaProfile())
                || model.schemaDigest() == null || !model.schemaDigest().matches("[0-9a-f]{64}")) {
            throw new BusinessException(OtaFirmwareErrorCode.MODEL_CONFLICT);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaFirmware firmware = new OtaFirmware(Uuid7.generate(), tenant, projectId, account, deviceTypeId,
                thingModelVersionId, model.productKey(), firmwareVersion, model.schemaDigestAlgorithm(),
                model.schemaDigest(), model.schemaProfile(), "DRAFT", 0, now, null);
        repository.create(firmware, keyDigest, requestDigest);
        record(firmware, account, "ota.firmware.created");
        return firmware;
    }

    /** 全部项目成员可读取包含取消终态的游标页。 */
    @Transactional(readOnly = true)
    public CursorPage<OtaFirmware> list(UUID projectId, String cursor, int limit) {
        projects.requireRoleInProject(projectId);
        return repository.page(projectId, cursor, limit);
    }

    /** 不存在与错项目一律返回70001。 */
    @Transactional(readOnly = true)
    public OtaFirmware find(UUID projectId, UUID firmwareId) {
        projects.requireRoleInProject(projectId);
        return repository.find(projectId, firmwareId, false).orElseThrow(OtaFirmwareService::notFound);
    }

    /** 在持续写许可和行锁下取消一次，终态仅允许取消前或当前修订重读。 */
    @Transactional
    public OtaFirmware cancel(UUID projectId, UUID firmwareId, String idempotencyKey, String expectedRevision) {
        requireWrite(projectId);
        requireKey(idempotencyKey);
        long expected = revision(expectedRevision);
        OtaFirmware firmware = repository.find(projectId, firmwareId, true).orElseThrow(OtaFirmwareService::notFound);
        if ("CANCELLED".equals(firmware.status())) {
            if (expected != firmware.revision() && expected != firmware.revision() - 1) { throw conflict(); }
            return firmware;
        }
        if (!"DRAFT".equals(firmware.status()) || expected != firmware.revision()
                || firmware.revision() == Long.MAX_VALUE) { throw conflict(); }
        Instant cancelled = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (cancelled.isBefore(firmware.createdAt())) { cancelled = firmware.createdAt(); }
        repository.cancel(projectId, firmwareId, expected, cancelled);
        uploads.cancelForFirmware(firmware.tenantId(), projectId, firmwareId);
        OtaFirmware result = repository.find(projectId, firmwareId, false).orElseThrow(OtaFirmwareService::notFound);
        record(result, TenantContext.require().accountId(), "ota.firmware.cancelled");
        return result;
    }

    /** 锁前角色、真实tenant、ACTIVE持续许可、锁后角色复核顺序不可调整。 */
    private UUID requireWrite(UUID projectId) {
        requireManagementRole(projectId);
        UUID tenant = projects.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(tenant, projectId);
        requireManagementRole(projectId);
        return tenant;
    }

    /** 非成员沿用项目错误，成员写权限只允许OWNER和ADMIN。 */
    private void requireManagementRole(UUID projectId) {
        ProjectRole role = projects.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(OtaFirmwareErrorCode.FORBIDDEN);
        }
    }

    /** 固件展示版本保留原文，拒绝空白、控制字符和不成对代理码元。 */
    private static void requireVersion(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) {
            throw invalid();
        }
    }

    /** 领域入口同步强制幂等键，不靠HTTP过滤器隐式提供安全性。 */
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) { throw invalid(); }
    }

    /** 非负十进制字符串严格转换为long，不做截断或溢出归约。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) { throw invalid(); }
        try { return Long.parseLong(value); } catch (NumberFormatException exception) { throw invalid(); }
    }

    /** 域和每个UTF8字段均明确分帧，避免拼接碰撞或存储原始幂等键。 */
    private static String digest(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((domain + "\0").getBytes(StandardCharsets.US_ASCII));
            for (String value : fields) {
                byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) { digest.update(bytes); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时缺少SHA256", exception);
        }
    }

    /** 审计只记录领域身份、状态及修订，失败会回滚同一业务事务。 */
    private void record(OtaFirmware firmware, UUID account, String action) {
        audit.record(new AuditLogEntry(firmware.tenantId(), firmware.projectId(), account,
                "ota_firmware", firmware.id(), action,
                Map.of("status", firmware.status(), "revision", Long.toString(firmware.revision()))));
    }
    /** 返回安全参数错误。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 返回精确状态冲突。 */
    private static BusinessException conflict() { return new BusinessException(OtaFirmwareErrorCode.STATE_CONFLICT); }
    /** 返回不区分归属的不存在错误。 */
    private static BusinessException notFound() { return new BusinessException(OtaFirmwareErrorCode.NOT_FOUND); }
}
