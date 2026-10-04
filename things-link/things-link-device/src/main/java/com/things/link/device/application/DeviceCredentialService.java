package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceCredential;
import com.things.link.device.domain.DeviceCredentialRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.cache.CacheResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 设备凭据应用服务。明文密钥只在创建时一次性返回，库中仅存 SHA-256 哈希。 */
@Service
public class DeviceCredentialService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DeviceCredentialRepository repository;
    private final DeviceRepository deviceRepository;
    private final DeviceTypeRepository typeRepository;
    private final ProjectService projectService;
    /** 原事务项目持续写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 安全凭据事实提交后广播统一失效事件。 */
    private final CacheInvalidationPublisher cacheInvalidationPublisher;
    /** 凭据写入前锁设备，原事务推进配置代次及关闭旧事实，提交后尽力踢连接。 */
    private final DeviceAccessControlService accessControl;

    /**
     * @param repository 凭据仓储
     * @param deviceRepository 设备仓储
     * @param typeRepository 设备类型仓储
     * @param projectService 项目成员与真实归属端口
     * @param lifecycle 原事务项目持续写许可
     * @param cacheInvalidationPublisher 提交后缓存失效发布器
     * @param accessControl 凭据锁序、配置代次及原子关闭端口
     */
    public DeviceCredentialService(DeviceCredentialRepository repository, DeviceRepository deviceRepository,
                                   DeviceTypeRepository typeRepository, ProjectService projectService,
                                   ProjectLifecycleAccessService lifecycle, CacheInvalidationPublisher cacheInvalidationPublisher,
                                   DeviceAccessControlService accessControl) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.projectService = projectService;
        this.lifecycle = lifecycle;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
        this.accessControl = accessControl;
    }

    /** @param projectId 项目 ID @param deviceId 设备 ID @return 有效凭据列表（不含明文） */
    @Transactional(readOnly = true)
    public List<DeviceCredential> list(UUID projectId, UUID deviceId) {
        requireMember(projectId); requireDevice(projectId, deviceId);
        return repository.findByDevice(projectId, deviceId);
    }

    /** 生成新的一机一密凭据，旧凭据同步作废，返回携带明文密钥的凭据对象。 */
    @Transactional
    public DeviceCredential generate(UUID projectId, UUID deviceId) {
        requireManager(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireManager(projectId);
        accessControl.lockCredentialDevice(ownerTenant, projectId, deviceId);
        Device device = requireDevice(projectId, deviceId);
        requireNotSubDevice(projectId, device);
        byte[] secret = new byte[32]; RANDOM.nextBytes(secret);
        String plainSecret = HexFormat.of().formatHex(secret);
        String hash = sha256(plainSecret);
        // 先作废旧凭据再创建新凭据，避免唯一索引短暂冲突。
        repository.revokeByDeviceAndType(projectId, deviceId, DeviceCredential.AuthType.ACCESS_TOKEN);
        DeviceCredential credential = new DeviceCredential(Uuid7.generate(), ownerTenant, projectId, deviceId,
                DeviceCredential.AuthType.ACCESS_TOKEN, hash, "设备密钥", null, null, null,
                plainSecret, Instant.now());
        repository.create(credential);
        publishCredentialInvalidation(deviceId, repository.incrementCredentialVersion(projectId, deviceId));
        accessControl.invalidateCredentials(ownerTenant, projectId, deviceId);
        return credential;
    }

    /** 作废指定凭据。 */
    @Transactional
    public void revoke(UUID projectId, UUID deviceId, UUID id) {
        requireManager(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireManager(projectId);
        accessControl.lockCredentialDevice(ownerTenant, projectId, deviceId);
        requireDevice(projectId, deviceId);
        if (!repository.softDelete(projectId, deviceId, id))
            throw new BusinessException(DeviceErrorCode.CREDENTIAL_NOT_FOUND);
        publishCredentialInvalidation(deviceId, repository.incrementCredentialVersion(projectId, deviceId));
        accessControl.invalidateCredentials(ownerTenant, projectId, deviceId);
    }

    /** 凭据事务提交后按设备定向驱逐所有实例的成功认证缓存。 */
    private void publishCredentialInvalidation(UUID deviceId, long version) {
        cacheInvalidationPublisher.publishAfterCommit(new CacheInvalidationEvent(
                Uuid7.generate(), CacheResource.DEVICE_CREDENTIAL, CacheInvalidationOperation.REVOKE,
                deviceId, null, version, 0L, Instant.now()));
    }

    /** @param input 原文 @return SHA-256 十六进制摘要 */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JVM 没有 SHA-256", e); }
    }

    private Device requireDevice(UUID projectId, UUID deviceId) {
        return deviceRepository.findById(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
    }

    /** 子设备经网关间接接入，不支持独立凭据（决策 2 的凭据签发层拒绝）。 */
    private void requireNotSubDevice(UUID projectId, Device device) {
        if (device.deviceTypeId() == null) {
            return;
        }
        DeviceType type = typeRepository.findById(projectId, device.deviceTypeId())
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        if (type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) {
            throw new BusinessException(DeviceErrorCode.SUB_DEVICE_CREDENTIAL_FORBIDDEN);
        }
    }

    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }

    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
    }
}
