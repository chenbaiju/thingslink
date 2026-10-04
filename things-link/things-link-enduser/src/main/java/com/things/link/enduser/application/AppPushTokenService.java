package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.domain.AppPushTokenRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * App 安装实例注册、轮换与吊销用例（G2-A2a）。
 *
 * <p>同一 {@code tenant + appUser + installation} 始终复用事实 ID。重新注册既可轮换厂商 token/provider，
 * 也可恢复已吊销实例；明文只从 API 参数流到加密端口，不进入领域对象、返回值或日志。
 */
@Service
public class AppPushTokenService {

    /** token 合理上限，与 API DTO 一致，避免绕过 Controller 时写入无界密文。 */
    private static final int MAX_TOKEN_LENGTH = 4096;

    /** 安装实例仓储。 */
    private final AppPushTokenRepository repository;
    /** 敏感 token 加密端口。 */
    private final PushTokenCipher cipher;
    /** 可控业务时钟。 */
    private final Clock clock;
    /** ADR 0064 决策 2/4：当前项目只决定写入资格，不改变安装实例的跨项目共用归属。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /**
     * 生产构造使用 UTC 系统时钟。
     *
     * @param repository 安装实例仓储
     * @param cipher token 加密端口
     * @param projectWriteGuard 原事务项目写许可
     */
    @Autowired
    public AppPushTokenService(AppPushTokenRepository repository, PushTokenCipher cipher,
                               AppProjectWriteGuard projectWriteGuard) {
        this(repository, cipher, projectWriteGuard, Clock.systemUTC());
    }

    /**
     * 测试构造允许冻结时钟，避免用时间窗口模糊断言。
     *
     * @param repository 安装实例仓储
     * @param cipher token 加密端口
     * @param projectWriteGuard 原事务项目写许可
     * @param clock 可控业务时钟
     */
    AppPushTokenService(AppPushTokenRepository repository, PushTokenCipher cipher,
                        AppProjectWriteGuard projectWriteGuard, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
        this.projectWriteGuard = projectWriteGuard;
    }

    /**
     * 注册或原位轮换安装实例。
     *
     * @param tenantId JWT 恢复的租户 ID
     * @param projectId 已验证 JWT 的当前项目 ID，仅作为写入许可来源
     * @param appUserId JWT subject
     * @param installationId 客户端稳定安装实例 UUID
     * @param provider 厂商通道
     * @param plainToken 厂商 token 明文
     */
    @Transactional
    public void register(UUID tenantId, UUID projectId, UUID appUserId, UUID installationId,
                         AppPushToken.Provider provider, String plainToken) {
        // ADR 0064 决策 2/4：先持项目许可，再沿安装advisory与行锁注册，冻结不能轮换或恢复共用安装。
        projectWriteGuard.requireWritable(tenantId, projectId);
        requireArguments(tenantId, appUserId, installationId, provider, plainToken);
        repository.lockRegistration(tenantId, appUserId, installationId);
        Instant now = clock.instant();
        boolean[] created = {false};
        AppPushToken token = repository.findForUpdate(tenantId, appUserId, installationId)
                .map(existing -> new AppPushToken(
                        existing.id(), tenantId, appUserId, installationId, provider,
                        AppPushToken.Status.ACTIVE, existing.createdAt(), now, null))
                .orElseGet(() -> {
                    created[0] = true;
                    return new AppPushToken(
                            Uuid7.generate(), tenantId, appUserId, installationId, provider,
                            AppPushToken.Status.ACTIVE, now, now, null);
                });
        EncryptedPushToken encrypted = cipher.encrypt(token, plainToken);
        int affected = created[0] ? repository.insert(token, encrypted) : repository.activate(token, encrypted);
        if (affected != 1) {
            // advisory lock 应确保冲突不发生；出现 0 行表示写路径绕过合同或 RLS 上下文错误，必须回滚。
            throw new IllegalStateException("PUSH 安装实例注册未写入唯一事实");
        }
    }

    /**
     * 幂等吊销当前用户的安装实例；不存在或已吊销均按成功处理。
     *
     * @param tenantId JWT 恢复的租户 ID
     * @param projectId 已验证 JWT 的当前项目 ID，仅作为写入许可来源
     * @param appUserId JWT subject
     * @param installationId 安装实例 UUID
     */
    @Transactional
    public void revoke(UUID tenantId, UUID projectId, UUID appUserId, UUID installationId) {
        // 冻结项目无权改写其他项目共用的安装；即使目标不存在，也须先拒绝而非幂等返回成功。
        projectWriteGuard.requireWritable(tenantId, projectId);
        if (tenantId == null || appUserId == null || installationId == null) {
            throw new IllegalArgumentException("PUSH 安装实例身份不能为空");
        }
        repository.revoke(tenantId, appUserId, installationId, clock.instant());
    }

    /**
     * 保护 service 直调路径，不能只依赖 HTTP Bean Validation。
     *
     * @param tenantId JWT 恢复的租户 ID
     * @param appUserId JWT subject
     * @param installationId 客户端稳定安装实例 UUID
     * @param provider 厂商通道
     * @param plainToken 厂商 token 明文
     */
    private void requireArguments(UUID tenantId, UUID appUserId, UUID installationId,
                                  AppPushToken.Provider provider, String plainToken) {
        if (tenantId == null || appUserId == null || installationId == null || provider == null) {
            throw new IllegalArgumentException("PUSH 安装实例身份与厂商不能为空");
        }
        if (plainToken == null || plainToken.isBlank() || plainToken.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("PUSH 厂商 token 长度非法");
        }
    }
}
