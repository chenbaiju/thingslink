package com.things.link.enduser.application;

import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppDeviceBindToken;
import com.things.link.enduser.domain.AppDeviceBindTokenRepository;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 独立主控转移用例（ADR 0037、G2-A1e）。
 *
 * <h2>两阶段能力交付</h2>
 * 当前 ACTIVE PRIMARY 先签发绑定自身身份的一次性 TRANSFER 令牌；另一名已有 ACTIVE
 * 项目角色的 App 用户消费。令牌不携带可伪造的 tenant/project/issuer 输入，三者均来自
 * 已认证上下文与数据库事实。
 *
 * <h2>一个事务、一个设备级串行点</h2>
 * 消费先持有项目写许可，再锁令牌与设备当前 PRIMARY（ADR 0064 决策 2/4）。旧 PRIMARY 原关系降为 MEMBER，接收者既有关系
 * 原位升为 PRIMARY，或在尚未共享时建立 PRIMARY；最后消费令牌并写审计。任一步异常都
 * 回滚，不能出现无主控半状态，也不以“解绑 + CLAIM”伪装转移。
 */
@Service
public class DeviceTransferService {

    /** TRANSFER 明文固定使用 256 bit 加密随机数。 */
    private static final int TOKEN_BYTES = 32;

    /** 转移能力窗口与 CLAIM 一致，缩短旧主控凭据的可利用时间。 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /** 哈希命中后的业务复核上限。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 极低概率哈希碰撞时的本地重生上限。 */
    private static final int GENERATION_ATTEMPTS = 3;

    /** 加密安全随机源。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 设备公开查询端口，只验证设备仍存在于当前项目。 */
    private final AppDeviceDataPlaneService deviceDataPlane;

    /** ADR 0064 决策 2/4 要求转移能力在原事务持有项目写许可。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /** TRANSFER 令牌权威事实。 */
    private final AppDeviceBindTokenRepository tokenRepository;

    /** App 项目角色前置门禁。 */
    private final AppUserRoleRepository roleRepository;

    /** 用户—设备关系权威事实。 */
    private final AppUserDeviceRepository bindingRepository;

    /** 转移安全审计。 */
    private final AuditLogService auditLogService;

    /**
     * 创建主控转移服务。
     *
     * @param deviceDataPlane 设备查询端口
     * @param projectWriteGuard 原事务项目写许可
     * @param tokenRepository 令牌仓储
     * @param roleRepository 项目角色仓储
     * @param bindingRepository 关系仓储
     * @param auditLogService 审计服务
     */
    public DeviceTransferService(AppDeviceDataPlaneService deviceDataPlane,
                                 AppProjectWriteGuard projectWriteGuard,
                                 AppDeviceBindTokenRepository tokenRepository,
                                 AppUserRoleRepository roleRepository,
                                 AppUserDeviceRepository bindingRepository,
                                 AuditLogService auditLogService) {
        this.deviceDataPlane = deviceDataPlane;
        this.projectWriteGuard = projectWriteGuard;
        this.tokenRepository = tokenRepository;
        this.roleRepository = roleRepository;
        this.bindingRepository = bindingRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 当前主控为设备签发一次性转移令牌。
     *
     * @param tenantId App JWT 租户
     * @param projectId App JWT 项目
     * @param appUserId 当前 App 用户
     * @param deviceId 目标设备
     * @return 只返回一次的明文令牌
     */
    @Transactional
    public DeviceTransferIssuedToken issue(UUID tenantId,
                                           UUID projectId,
                                           UUID appUserId,
                                           UUID deviceId) {
        // ADR 0064 决策 2/4：先持有原事务项目许可，再由当前 PRIMARY 授权签发转移能力。
        long projectGeneration = projectWriteGuard.requireWritable(tenantId, projectId);
        requireActiveRole(tenantId, projectId, appUserId);
        if (deviceDataPlane.detail(projectId, deviceId).isEmpty()) {
            throw new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND);
        }
        AppUserDevice primary = bindingRepository.findActivePrimary(projectId, deviceId)
                .filter(binding -> binding.appUserId().equals(appUserId))
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND));

        for (int generation = 0; generation < GENERATION_ATTEMPTS; generation++) {
            byte[] raw = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(raw);
            String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            AppDeviceBindToken token = new AppDeviceBindToken(
                    Uuid7.generate(), tenantId, projectId, projectGeneration, deviceId,
                    AppDeviceBindToken.Purpose.TRANSFER, AppUserDevice.RelationRole.PRIMARY,
                    appUserId, now.plus(TOKEN_TTL), 0, MAX_ATTEMPTS,
                    null, null, now, now);
            if (tokenRepository.save(token, sha256(plaintext))) {
                auditIssued(primary, token, appUserId);
                return new DeviceTransferIssuedToken(plaintext, token.expiresAt());
            }
        }
        throw new IllegalStateException("无法生成唯一主控转移令牌");
    }

    /**
     * 接收并原子完成主控转移。
     *
     * <p>预期令牌拒绝使用 noRollbackFor 保存已计入的哈希复核次数；关系变更开始后只允许
     * 成功或内部异常回滚，禁止抛业务异常留下旧主控已降级的半状态。
     *
     * @param tenantId 接收者 App JWT 租户
     * @param projectId 接收者 App JWT 项目
     * @param appUserId 接收者
     * @param plaintext 明文转移令牌
     * @return 新 PRIMARY 关系
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DeviceTransferResult consume(UUID tenantId,
                                        UUID projectId,
                                        UUID appUserId,
                                        String plaintext) {
        // noRollbackFor 会保留业务拒绝前的写入；许可须先于计数与主控变更，也须阻止冻结后的幂等回读。
        long projectGeneration = projectWriteGuard.requireWritable(tenantId, projectId);
        AppDeviceBindToken token = tokenRepository.findByProjectAndHashForUpdate(projectId, sha256(plaintext))
                .orElseThrow(DeviceTransferService::invalidToken);
        // ADR0073：先比较签发代次，旧能力不得进入关系读取、尝试计数或幂等回读。
        if (token.projectGeneration() != projectGeneration) {
            throw invalidToken();
        }
        requireActiveRole(tenantId, projectId, appUserId);
        if (!token.tenantId().equals(tenantId)
                || token.purpose() != AppDeviceBindToken.Purpose.TRANSFER
                || token.targetRole() != AppUserDevice.RelationRole.PRIMARY
                || token.issuedByAppUserId() == null) {
            throw invalidToken();
        }
        if (token.consumedAt() != null) {
            return idempotentReplay(projectId, appUserId, token);
        }
        if (token.attemptCount() >= token.maxAttempts()
                || tokenRepository.incrementAttempt(projectId, token.id()) != 1) {
            throw invalidToken();
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!token.expiresAt().isAfter(now)
                || deviceDataPlane.detail(projectId, token.deviceId()).isEmpty()) {
            throw invalidToken();
        }
        if (token.issuedByAppUserId().equals(appUserId)) {
            throw new BusinessException(EndUserErrorCode.DEVICE_TRANSFER_CONFLICT);
        }
        AppUserDevice previousPrimary = bindingRepository
                .findActivePrimaryForUpdate(projectId, token.deviceId())
                .filter(binding -> binding.appUserId().equals(token.issuedByAppUserId()))
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DEVICE_TRANSFER_CONFLICT));
        AppUserDevice receiver = bindingRepository
                .findActiveForUpdate(projectId, appUserId, token.deviceId()).orElse(null);
        if (receiver != null && receiver.relationRole() == AppUserDevice.RelationRole.PRIMARY) {
            throw new BusinessException(EndUserErrorCode.DEVICE_TRANSFER_CONFLICT);
        }

        if (bindingRepository.demotePrimaryToMember(projectId, previousPrimary.id(),
                previousPrimary.appUserId(), token.deviceId()) != 1) {
            throw new IllegalStateException("旧主控关系在转移事务内发生漂移");
        }

        AppUserDevice newPrimary = receiver;
        if (receiver == null) {
            newPrimary = new AppUserDevice(
                    Uuid7.generate(), tenantId, projectId, appUserId, token.deviceId(),
                    AppUserDevice.RelationRole.PRIMARY, AppUserDevice.Status.ACTIVE, now);
            if (bindingRepository.createActivePrimary(newPrimary) != 1) {
                throw new IllegalStateException("接收者 PRIMARY 关系并发冲突");
            }
        } else {
            if (bindingRepository.promoteActiveToPrimary(projectId, receiver.id(),
                    appUserId, token.deviceId()) != 1) {
                throw new IllegalStateException("接收者关系在转移事务内发生漂移");
            }
            // 仓储更新后同步内存投影，避免后续审计或响应误把新主控继续当作旧共享角色。
            newPrimary = new AppUserDevice(
                    receiver.id(), receiver.tenantId(), receiver.projectId(), receiver.appUserId(),
                    receiver.deviceId(), AppUserDevice.RelationRole.PRIMARY,
                    receiver.status(), receiver.createdAt());
        }

        if (tokenRepository.consume(projectId, token.id(), appUserId, now) != 1) {
            throw new IllegalStateException("主控转移令牌消费状态发生非预期漂移");
        }
        auditCompleted(previousPrimary, newPrimary, appUserId);
        return DeviceTransferResult.from(newPrimary);
    }

    /** App 用户必须仍有当前项目 ACTIVE 角色，且租户轴与 JWT 一致。 */
    private void requireActiveRole(UUID tenantId, UUID projectId, UUID appUserId) {
        AppUserRole role = roleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE || !role.tenantId().equals(tenantId)) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
    }

    /** 同接收者重放只在其仍是设备当前 PRIMARY 时幂等成功。 */
    private DeviceTransferResult idempotentReplay(UUID projectId,
                                                  UUID appUserId,
                                                  AppDeviceBindToken token) {
        if (!appUserId.equals(token.consumedByAppUserId())) {
            throw invalidToken();
        }
        AppUserDevice primary = bindingRepository.findActivePrimary(projectId, token.deviceId())
                .filter(binding -> binding.appUserId().equals(appUserId))
                .orElseThrow(DeviceTransferService::invalidToken);
        return DeviceTransferResult.from(primary);
    }

    /** 签发只审计令牌元数据，不记录明文或哈希。 */
    private void auditIssued(AppUserDevice primary, AppDeviceBindToken token, UUID actorId) {
        auditLogService.record(new AuditLogEntry(
                primary.tenantId(), primary.projectId(), null,
                "app_device_bind_token", token.id(), "enduser.device.transfer.issued",
                Map.of("actorType", "APP_USER", "actorId", actorId,
                        "deviceId", primary.deviceId(), "expiresAt", token.expiresAt())));
    }

    /** 完成审计同时记录旧/新关系，供事后还原主控链。 */
    private void auditCompleted(AppUserDevice previousPrimary,
                                AppUserDevice newPrimary,
                                UUID actorId) {
        auditLogService.record(new AuditLogEntry(
                previousPrimary.tenantId(), previousPrimary.projectId(), null,
                "app_user_device", newPrimary.id(), "enduser.device.transfer.completed",
                Map.of("actorType", "APP_USER", "actorId", actorId,
                        "deviceId", previousPrimary.deviceId(),
                        "previousPrimaryAppUserId", previousPrimary.appUserId(),
                        "previousPrimaryBindingId", previousPrimary.id(),
                        "newPrimaryAppUserId", newPrimary.appUserId(),
                        "newPrimaryBindingId", newPrimary.id(),
                        "previousPrimaryRole", "MEMBER")));
    }

    /** 统一转移令牌不可用错误，避免暴露令牌存在性和状态。 */
    private static BusinessException invalidToken() {
        return new BusinessException(EndUserErrorCode.DEVICE_TRANSFER_TOKEN_INVALID);
    }

    /** 对明文做固定 SHA-256；标准 Java 运行时必须提供该算法。 */
    private static byte[] sha256(String plaintext) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }
}
