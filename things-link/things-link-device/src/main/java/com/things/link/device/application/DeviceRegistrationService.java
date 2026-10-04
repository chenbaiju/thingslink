package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceCredential;
import com.things.link.device.domain.DeviceCredentialRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.DeviceAccessScope;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 一型一密动态注册应用服务。
 *
 * <p>公开入口没有用户 TenantContext：先通过 project 模块公开契约定位最小范围，再在同一事务连接上
 * 注入 RLS，之后所有设备表访问仍受 project_id 策略保护。项目不存在、产品不存在与密钥错误统一返回
 * 30026，避免公开端点成为项目/产品枚举器。</p>
 */
@Service
public class DeviceRegistrationService {

    /** 设备 Access Token 使用 256 位随机量。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 项目公开接入范围查询。 */
    private final ProjectService projectService;

    /** 架构§8.2要求公开注册以可信owner二元组读取数据库配额，不能依赖不存在的账号上下文。 */
    private final ProjectQuotaService quotaService;

    /** 设备类型仓储。 */
    private final DeviceTypeRepository typeRepository;

    /** 设备仓储。 */
    private final DeviceRepository deviceRepository;

    /** 设备凭据仓储。 */
    private final DeviceCredentialRepository credentialRepository;

    /** S12-2a1c集中保证注册事务连接上的完整租户与项目RLS范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /** 显式事务模板允许先定位无 RLS 的项目，再打开设备写事务。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建动态注册服务。
     *
     * @param projectService 项目公开接入范围端口
     * @param quotaService 权威设备配额端口
     * @param typeRepository 设备类型仓储
     * @param deviceRepository 设备仓储
     * @param credentialRepository 设备凭据仓储
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param transactionTemplate 包裹设备写入的事务模板
     */
    public DeviceRegistrationService(ProjectService projectService, ProjectQuotaService quotaService,
                                     DeviceTypeRepository typeRepository,
                                     DeviceRepository deviceRepository,
                                     DeviceCredentialRepository credentialRepository,
                                     TransactionLocalRlsScope transactionLocalRlsScope,
                                     TransactionTemplate transactionTemplate) {
        this.projectService = projectService;
        this.quotaService = quotaService;
        this.typeRepository = typeRepository;
        this.deviceRepository = deviceRepository;
        this.credentialRepository = credentialRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 原子创建设备及其首个 Access Token。
     *
     * @param projectKey 项目公开标识
     * @param productKey 产品公开标识
     * @param productSecret 一型一密产品密钥
     * @param deviceKey 待注册设备标识
     * @return 一次可见注册结果
     */
    public RegistrationResult register(String projectKey, String productKey,
                                       String productSecret, String deviceKey) {
        DeviceAccessScope scope = projectService.findDeviceAccessScope(projectKey)
                .orElseThrow(DeviceRegistrationService::denied);
        RegistrationResult result = transactionTemplate.execute(status ->
                registerInScope(scope, productKey, productSecret, deviceKey));
        if (result == null) {
            throw new IllegalStateException("动态注册事务没有返回结果");
        }
        return result;
    }

    /** 在已打开的事务连接中注入 RLS，并完成产品确权、设备和凭据写入。 */
    private RegistrationResult registerInScope(DeviceAccessScope scope, String productKey,
                                               String productSecret, String deviceKey) {
        // project公开解析结果是注册唯一可信归属，且此处已经进入TransactionTemplate真实事务。
        transactionLocalRlsScope.establish(scope.tenantId(), scope.projectId());
        DeviceType type = typeRepository.findPublishedByProductKey(scope.projectId(), productKey)
                .orElseThrow(DeviceRegistrationService::denied);
        if (type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) {
            // 子设备无独立接入：即便存量/被误加的产品密钥残存，公开入口也绝不发放子设备凭据。
            throw denied();
        }
        if (!constantTimeMatches(productSecret, type.productSecretHash())) {
            throw denied();
        }

        // D-116：与控制面/网关共用owner锁；必须先确权再取锁，避免未认证请求探测配额或占用租户写锁。
        deviceRepository.lockTenantDeviceQuota(scope.tenantId());
        // 缺口2.2的重复注册合同是冲突而非重签；锁后重查使并发后到请求在满额时也保持30021。
        if (deviceRepository.findByDeviceKey(scope.projectId(), deviceKey).isPresent()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_KEY_CONFLICT);
        }
        QuotaStatus quotaStatus = quotaService.deviceQuotaStatus(scope.tenantId(), scope.projectId());
        if (quotaStatus == QuotaStatus.HARD_LIMIT || quotaStatus == QuotaStatus.DEGRADED) {
            throw new BusinessException(DeviceErrorCode.DEVICE_QUOTA_EXCEEDED);
        }
        // 配额读取异常直接穿出事务；设备、INITIAL及凭据只有在权威配额允许时才写入并一同提交。
        UUID deviceId = Uuid7.generate();
        Device device = new Device(deviceId, scope.tenantId(), scope.projectId(), type.id(), null,
                deviceKey, deviceKey, null, Device.Status.INACTIVE, null, null, Instant.now());
        try {
            deviceRepository.create(device);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(DeviceErrorCode.DEVICE_KEY_CONFLICT);
        }

        String accessToken = randomHex(32);
        DeviceCredential credential = new DeviceCredential(Uuid7.generate(), scope.tenantId(), scope.projectId(),
                deviceId, DeviceCredential.AuthType.ACCESS_TOKEN, sha256(accessToken), "动态注册密钥",
                null, null, null, null, Instant.now());
        credentialRepository.create(credential);
        return new RegistrationResult(deviceId, deviceKey, accessToken);
    }

    /** 固定时间比较摘要，避免逐字符 equals 泄漏正确前缀长度。 */
    private static boolean constantTimeMatches(String plainSecret, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256(plainSecret).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** 生成指定字节数的高熵十六进制密钥。 */
    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    /** SHA-256 仅用于摘要 256 位随机密钥，不用于低熵用户口令。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }

    /** @return 不区分项目、产品和密钥的统一拒绝异常 */
    private static BusinessException denied() {
        return new BusinessException(DeviceErrorCode.DEVICE_REGISTRATION_DENIED);
    }

    /** @param deviceId 设备 ID @param deviceKey 设备标识 @param accessToken 一次可见密钥 */
    public record RegistrationResult(UUID deviceId, String deviceKey, String accessToken) {
    }
}
