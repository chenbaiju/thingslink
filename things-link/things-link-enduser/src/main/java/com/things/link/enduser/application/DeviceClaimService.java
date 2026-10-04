package com.things.link.enduser.application;

import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppDeviceBindToken;
import com.things.link.enduser.domain.AppDeviceBindTokenRepository;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.ProjectRoutingContext;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * CLAIM 令牌签发与原子消费用例（ADR 0037、G2-A1c）。
 *
 * <h2>签发面与消费面分离</h2>
 * 控制台 OWNER/ADMIN 只能为当前项目内存在且尚无 PRIMARY 的设备签发；App 用户只能在
 * access token 已绑定的项目消费。两边都不接受客户端提供 tenantId，防止把隔离键变成
 * 越权输入。
 *
 * <h2>消费事务</h2>
 * 哈希行先 {@code FOR UPDATE}，同一令牌的双花因此串行。命中后的尝试递增、PRIMARY 关系
 * 写入和消费 CAS 同属一个事务；预期业务拒绝使用 {@code noRollbackFor} 保留已发生的复核
 * 尝试，而任何非业务异常仍回滚关系与消费事实。
 */
@Service
public class DeviceClaimService {

    /** CLAIM 明文随机字节数；256 bit 不能降级为短数字码。 */
    private static final int TOKEN_BYTES = 32;

    /** 令牌固定有效期；与架构 §11.4 的 G2-A1c 合同一致。 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /** 哈希命中后允许进入业务复核的最大次数。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 极低概率哈希碰撞时的本地重新生成上限。 */
    private static final int GENERATION_ATTEMPTS = 3;

    /** 加密安全随机源；实例复用避免重复向操作系统请求初始化。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 项目成员与路由上下文入口。 */
    private final ProjectService projectService;

    /** 设备模块公开的 application 查询端口。 */
    private final AppDeviceDataPlaneService deviceDataPlane;

    /** 令牌权威事实仓储。 */
    private final AppDeviceBindTokenRepository tokenRepository;

    /** 项目角色前置门禁。 */
    private final AppUserRoleRepository roleRepository;

    /** 设备关系权威事实仓储。 */
    private final AppUserDeviceRepository bindingRepository;

    /** S12-2a1e 以控制台路由或 App JWT 身份建立完整事务局部 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** ADR0064决策4：App消费必须在计数、令牌锁和幂等回读之前持有项目写许可。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /**
     * 创建 CLAIM 用例服务。
     *
     * @param projectService 项目服务
     * @param deviceDataPlane 设备 application 端口
     * @param tokenRepository 令牌仓储
     * @param roleRepository 项目角色仓储
     * @param bindingRepository 设备关系仓储
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param projectWriteGuard 加入原App消费事务的项目写许可
     */
    public DeviceClaimService(ProjectService projectService,
                              AppDeviceDataPlaneService deviceDataPlane,
                              AppDeviceBindTokenRepository tokenRepository,
                              AppUserRoleRepository roleRepository,
                              AppUserDeviceRepository bindingRepository,
                              TransactionLocalRlsScope transactionLocalRlsScope,
                              AppProjectWriteGuard projectWriteGuard) {
        this.projectService = projectService;
        this.deviceDataPlane = deviceDataPlane;
        this.tokenRepository = tokenRepository;
        this.roleRepository = roleRepository;
        this.bindingRepository = bindingRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.projectWriteGuard = projectWriteGuard;
    }

    /**
     * 为项目内设备签发一次性 CLAIM 令牌。
     *
     * @param projectId 项目 ID
     * @param deviceId  目标设备 ID
     * @return 只出现一次的明文令牌与过期时刻
     */
    @Transactional
    public DeviceClaimIssuedToken issue(UUID projectId, UUID deviceId) {
        requireManager(projectId);
        ProjectRoutingContext routing = projectService.requireRoutingContext(projectId);
        transactionLocalRlsScope.establish(routing.tenantId(), projectId);
        long projectGeneration = projectWriteGuard.requireWritable(routing.tenantId(), projectId);

        if (deviceDataPlane.detail(projectId, deviceId).isEmpty()) {
            throw new BusinessException(EndUserErrorCode.CLAIM_DEVICE_NOT_FOUND);
        }
        if (bindingRepository.findActivePrimary(projectId, deviceId).isPresent()) {
            throw new BusinessException(EndUserErrorCode.DEVICE_ALREADY_CLAIMED);
        }

        // ON CONFLICT DO NOTHING 让极低概率哈希碰撞不会把事务打入 aborted；最多本地重生三次。
        for (int generation = 0; generation < GENERATION_ATTEMPTS; generation++) {
            byte[] raw = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(raw);
            String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            byte[] hash = sha256(plaintext);
            Instant now = Instant.now();
            AppDeviceBindToken token = new AppDeviceBindToken(
                    Uuid7.generate(), routing.tenantId(), projectId, projectGeneration, deviceId,
                    AppDeviceBindToken.Purpose.CLAIM, AppUserDevice.RelationRole.PRIMARY,
                    null,
                    now.plus(TOKEN_TTL), 0, MAX_ATTEMPTS, null, null, now, now);
            if (tokenRepository.save(token, hash)) {
                return new DeviceClaimIssuedToken(plaintext, token.expiresAt());
            }
        }
        throw new IllegalStateException("无法生成唯一设备认领令牌");
    }

    /**
     * 消费 CLAIM 并建立 PRIMARY 关系。
     *
     * <p>相同消费人重放且原 PRIMARY 仍有效时返回原关系，处理“服务已提交但客户端丢失响应”；
     * 其他重放统一 60012。预期业务拒绝不回滚已经递增的有效哈希复核次数。
     *
     * @param tenantId 当前 App access token 的租户
     * @param projectId 当前 App access token 的项目
     * @param appUserId 当前 App 用户
     * @param plaintext 明文令牌
     * @return 新建或幂等复用的 PRIMARY 关系
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DeviceClaimResult consume(UUID tenantId, UUID projectId, UUID appUserId, String plaintext) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        long projectGeneration = projectWriteGuard.requireWritable(tenantId, projectId);
        byte[] hash = sha256(plaintext);
        AppDeviceBindToken token = tokenRepository.findByProjectAndHashForUpdate(projectId, hash)
                .orElseThrow(DeviceClaimService::invalidToken);

        // ADR0073：代次必须早于角色/关系读取、尝试计数与已消费幂等回读，拒绝路径零副作用。
        if (token.projectGeneration() != projectGeneration) {
            throw invalidToken();
        }
        requireActiveRole(projectId, appUserId);

        if (token.consumedAt() != null) {
            return idempotentReplay(projectId, appUserId, token);
        }
        if (token.attemptCount() >= token.maxAttempts()
                || tokenRepository.incrementAttempt(projectId, token.id()) != 1) {
            throw invalidToken();
        }

        Instant now = Instant.now();
        if (!token.expiresAt().isAfter(now)) {
            throw invalidToken();
        }
        if (!token.tenantId().equals(tenantId)
                || token.purpose() != AppDeviceBindToken.Purpose.CLAIM
                || token.targetRole() != AppUserDevice.RelationRole.PRIMARY) {
            throw invalidToken();
        }
        if (deviceDataPlane.detail(projectId, token.deviceId()).isEmpty()) {
            throw invalidToken();
        }

        // 若同一用户已通过另一条并发 CLAIM 成为主控，可安全消费本令牌并幂等返回现有关系。
        AppUserDevice existing = bindingRepository.findActive(projectId, appUserId, token.deviceId())
                .orElse(null);
        if (existing != null) {
            if (existing.relationRole() != AppUserDevice.RelationRole.PRIMARY) {
                throw new BusinessException(EndUserErrorCode.DEVICE_ALREADY_CLAIMED);
            }
            consumeOrFail(projectId, token.id(), appUserId, now);
            return DeviceClaimResult.from(existing);
        }
        if (bindingRepository.findActivePrimary(projectId, token.deviceId()).isPresent()) {
            throw new BusinessException(EndUserErrorCode.DEVICE_ALREADY_CLAIMED);
        }

        AppUserDevice binding = new AppUserDevice(
                Uuid7.generate(), tenantId, projectId, appUserId, token.deviceId(),
                AppUserDevice.RelationRole.PRIMARY, AppUserDevice.Status.ACTIVE, now);
        if (bindingRepository.createActivePrimary(binding) != 1) {
            // 唯一索引是最终仲裁。并发同用户成功时仍可幂等收敛；其他冲突稳定返回 60013。
            AppUserDevice concurrent = bindingRepository.findActive(projectId, appUserId, token.deviceId())
                    .orElse(null);
            if (concurrent == null || concurrent.relationRole() != AppUserDevice.RelationRole.PRIMARY) {
                throw new BusinessException(EndUserErrorCode.DEVICE_ALREADY_CLAIMED);
            }
            consumeOrFail(projectId, token.id(), appUserId, now);
            return DeviceClaimResult.from(concurrent);
        }

        consumeOrFail(projectId, token.id(), appUserId, now);
        return DeviceClaimResult.from(binding);
    }

    /** 要求控制台调用者具备 enduser:manage 对应的 OWNER/ADMIN 角色。 */
    private void requireManager(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (!role.canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
    }

    /** App 用户必须仍有 ACTIVE 项目角色；停用令牌不能烧掉认领令牌尝试次数。 */
    private void requireActiveRole(UUID projectId, UUID appUserId) {
        AppUserRole role = roleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
    }

    /** 同消费人重放只在原 PRIMARY 关系仍有效时幂等成功。 */
    private DeviceClaimResult idempotentReplay(UUID projectId,
                                               UUID appUserId,
                                               AppDeviceBindToken token) {
        if (!appUserId.equals(token.consumedByAppUserId())) {
            throw invalidToken();
        }
        AppUserDevice binding = bindingRepository.findActive(projectId, appUserId, token.deviceId())
                .filter(candidate -> candidate.relationRole() == AppUserDevice.RelationRole.PRIMARY)
                .orElseThrow(DeviceClaimService::invalidToken);
        return DeviceClaimResult.from(binding);
    }

    /** 消费 CAS 必须恰好命中一行；否则抛内部异常让整个关系事务回滚。 */
    private void consumeOrFail(UUID projectId, UUID tokenId, UUID appUserId, Instant consumedAt) {
        if (tokenRepository.consume(projectId, tokenId, appUserId, consumedAt) != 1) {
            throw new IllegalStateException("设备认领令牌消费状态发生非预期漂移");
        }
    }

    /** 统一令牌不可用错误，不能向调用方区分存在性或历史状态。 */
    private static BusinessException invalidToken() {
        return new BusinessException(EndUserErrorCode.DEVICE_CLAIM_TOKEN_INVALID);
    }

    /**
     * 对明文做固定 SHA-256；Java 运行时必须提供该标准算法。
     *
     * @param plaintext 令牌明文
     * @return 32 字节哈希
     */
    private static byte[] sha256(String plaintext) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }
}
