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
 * MEMBER/READ_ONLY 设备共享状态机（ADR 0037、G2-A1f）。
 *
 * <p>只有设备当前 ACTIVE PRIMARY 能签发绑定自身与目标角色的 SHARE 令牌。消费事务锁定
 * 令牌和当前 PRIMARY，确保主控变化后旧能力立即失效；SHARE 只建立新关系，不承担既有
 * 关系的提权或降权，避免陈旧令牌改写授权。
 */
@Service
public class DeviceShareService {

    /** SHARE 明文固定使用 256 bit 加密随机数。 */
    private static final int TOKEN_BYTES = 32;

    /** SHARE 与其他绑定能力保持相同的短窗口。 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /** 哈希命中后的最大业务复核次数。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 极低概率哈希碰撞时的本地重生上限。 */
    private static final int GENERATION_ATTEMPTS = 3;

    /** 加密安全随机源。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 设备存在性查询端口。 */
    private final AppDeviceDataPlaneService deviceDataPlane;

    /** ADR 0064 决策 2/4 要求共享能力在原事务持有项目写许可。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /** SHARE 令牌权威事实。 */
    private final AppDeviceBindTokenRepository tokenRepository;

    /** App 项目角色前置门禁。 */
    private final AppUserRoleRepository roleRepository;

    /** 用户—设备关系权威事实。 */
    private final AppUserDeviceRepository bindingRepository;

    /** 共享安全审计。 */
    private final AuditLogService auditLogService;

    /**
     * 创建设备共享服务。
     *
     * @param deviceDataPlane 设备查询端口
     * @param projectWriteGuard 原事务项目写许可
     * @param tokenRepository 令牌仓储
     * @param roleRepository 项目角色仓储
     * @param bindingRepository 关系仓储
     * @param auditLogService 审计服务
     */
    public DeviceShareService(AppDeviceDataPlaneService deviceDataPlane,
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
     * 当前主控签发指定共享角色的一次性令牌。
     *
     * @param tenantId App JWT 租户
     * @param projectId App JWT 项目
     * @param appUserId 当前 App 用户
     * @param deviceId 目标设备
     * @param targetRole MEMBER 或 READ_ONLY
     * @return 只返回一次的明文令牌
     */
    @Transactional
    public DeviceShareIssuedToken issue(UUID tenantId,
                                        UUID projectId,
                                        UUID appUserId,
                                        UUID deviceId,
                                        AppUserDevice.RelationRole targetRole) {
        // ADR 0064 决策 2/4：先在当前签发事务取得项目 SHARE 许可，再沿原 PRIMARY 授权签发能力。
        long projectGeneration = projectWriteGuard.requireWritable(tenantId, projectId);
        requireShareRole(targetRole);
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
            Instant now = Instant.now();
            AppDeviceBindToken token = new AppDeviceBindToken(
                    Uuid7.generate(), tenantId, projectId, projectGeneration, deviceId,
                    AppDeviceBindToken.Purpose.SHARE, targetRole, appUserId,
                    now.plus(TOKEN_TTL), 0, MAX_ATTEMPTS,
                    null, null, now, now);
            if (tokenRepository.save(token, sha256(plaintext))) {
                auditIssued(primary, token, appUserId);
                return new DeviceShareIssuedToken(plaintext, targetRole, token.expiresAt());
            }
        }
        throw new IllegalStateException("无法生成唯一设备共享令牌");
    }

    /**
     * 消费 SHARE 令牌并原子建立共享关系。
     *
     * <p>业务拒绝不回滚已计入的有效哈希复核次数；关系写入后只允许成功或内部异常整体
     * 回滚，不能把令牌消费与授权关系拆成两个提交。
     *
     * @param tenantId 接收者 App JWT 租户
     * @param projectId 接收者 App JWT 项目
     * @param appUserId 接收者
     * @param plaintext 明文 SHARE 令牌
     * @return 新共享关系
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DeviceShareResult consume(UUID tenantId,
                                     UUID projectId,
                                     UUID appUserId,
                                     String plaintext) {
        // noRollbackFor 会保留业务拒绝前的写入；许可必须早于尝试计数，也不能让已消费令牌绕过冻结。
        long projectGeneration = projectWriteGuard.requireWritable(tenantId, projectId);
        AppDeviceBindToken token = tokenRepository.findByProjectAndHashForUpdate(projectId, sha256(plaintext))
                .orElseThrow(DeviceShareService::invalidToken);
        // ADR0073：删除前能力不能进入关系读取、计数或幂等成功分支。
        if (token.projectGeneration() != projectGeneration) {
            throw invalidToken();
        }
        requireActiveRole(tenantId, projectId, appUserId);
        if (!token.tenantId().equals(tenantId)
                || token.purpose() != AppDeviceBindToken.Purpose.SHARE
                || !isShareRole(token.targetRole())
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

        Instant now = Instant.now();
        if (!token.expiresAt().isAfter(now)
                || deviceDataPlane.detail(projectId, token.deviceId()).isEmpty()) {
            throw invalidToken();
        }
        if (token.issuedByAppUserId().equals(appUserId)) {
            throw new BusinessException(EndUserErrorCode.DEVICE_SHARE_CONFLICT);
        }
        bindingRepository.findActivePrimaryForUpdate(projectId, token.deviceId())
                .filter(binding -> binding.appUserId().equals(token.issuedByAppUserId()))
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.DEVICE_SHARE_CONFLICT));
        if (bindingRepository.findActiveForUpdate(projectId, appUserId, token.deviceId()).isPresent()) {
            throw new BusinessException(EndUserErrorCode.DEVICE_SHARE_CONFLICT);
        }

        AppUserDevice shared = new AppUserDevice(
                Uuid7.generate(), tenantId, projectId, appUserId, token.deviceId(),
                token.targetRole(), AppUserDevice.Status.ACTIVE, now);
        if (bindingRepository.createActiveShared(shared) != 1) {
            throw new BusinessException(EndUserErrorCode.DEVICE_SHARE_CONFLICT);
        }
        if (tokenRepository.consume(projectId, token.id(), appUserId, now) != 1) {
            throw new IllegalStateException("设备共享令牌消费状态发生非预期漂移");
        }
        auditCompleted(shared, token.issuedByAppUserId(), appUserId);
        return DeviceShareResult.from(shared);
    }

    /** App 用户必须仍有当前项目 ACTIVE 角色，且租户轴与 JWT 一致。 */
    private void requireActiveRole(UUID tenantId, UUID projectId, UUID appUserId) {
        AppUserRole role = roleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE || !role.tenantId().equals(tenantId)) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
    }

    /** SHARE 只允许 MEMBER/READ_ONLY，不能成为 PRIMARY 的旁路。 */
    private static void requireShareRole(AppUserDevice.RelationRole role) {
        if (!isShareRole(role)) {
            throw new BusinessException(EndUserErrorCode.DEVICE_SHARE_CONFLICT);
        }
    }

    /** 判断角色是否属于 SHARE 的合法最小授权集合。 */
    private static boolean isShareRole(AppUserDevice.RelationRole role) {
        return role == AppUserDevice.RelationRole.MEMBER
                || role == AppUserDevice.RelationRole.READ_ONLY;
    }

    /** 同接收者重放只在关系仍保持令牌目标角色时幂等成功。 */
    private DeviceShareResult idempotentReplay(UUID projectId,
                                               UUID appUserId,
                                               AppDeviceBindToken token) {
        if (!appUserId.equals(token.consumedByAppUserId())) {
            throw invalidToken();
        }
        AppUserDevice relation = bindingRepository.findActive(projectId, appUserId, token.deviceId())
                .filter(binding -> binding.relationRole() == token.targetRole())
                .orElseThrow(DeviceShareService::invalidToken);
        return DeviceShareResult.from(relation);
    }

    /** 签发审计只保存能力元数据，不保存令牌明文或哈希。 */
    private void auditIssued(AppUserDevice primary, AppDeviceBindToken token, UUID actorId) {
        auditLogService.record(new AuditLogEntry(
                primary.tenantId(), primary.projectId(), null,
                "app_device_bind_token", token.id(), "enduser.device.share.issued",
                Map.of("actorType", "APP_USER", "actorId", actorId,
                        "deviceId", primary.deviceId(), "targetRole", token.targetRole().name(),
                        "expiresAt", token.expiresAt())));
    }

    /** 完成审计记录签发者、接收者和实际授权角色。 */
    private void auditCompleted(AppUserDevice shared, UUID issuerId, UUID actorId) {
        auditLogService.record(new AuditLogEntry(
                shared.tenantId(), shared.projectId(), null,
                "app_user_device", shared.id(), "enduser.device.share.completed",
                Map.of("actorType", "APP_USER", "actorId", actorId,
                        "deviceId", shared.deviceId(), "issuerAppUserId", issuerId,
                        "sharedAppUserId", shared.appUserId(),
                        "bindingId", shared.id(), "targetRole", shared.relationRole().name())));
    }

    /** 统一共享令牌不可用错误，避免暴露令牌存在性和状态。 */
    private static BusinessException invalidToken() {
        return new BusinessException(EndUserErrorCode.DEVICE_SHARE_TOKEN_INVALID);
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
