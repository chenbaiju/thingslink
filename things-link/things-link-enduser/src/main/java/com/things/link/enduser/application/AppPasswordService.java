package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRefreshTokenRepository;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户改密用例（ADR 0036）。
 *
 * <h2>改密 = 全会话撤销</h2>
 * 只换口令而不踢掉现有会话，攻击者手里的刷新令牌照样能一直续期，改密等于没做。因此本
 * 用例在同一个事务内更新 {@code password_hash} + {@code password_changed_at} 并撤销该用户
 * 全部 {@code app_refresh_token}（{@link AppRefreshTokenRepository#revokeAllForUser}）。
 *
 * <p>LOCKED / 项目角色停用<b>不</b>主动撤销会话 —— 它们靠刷新时回库复验（{@code AppSessionService}）
 * 生效，短时访问令牌（15 分钟）覆盖残余窗口。
 */
@Service
public class AppPasswordService {

    /** 口令最短长度，与预置账号 {@code EndUserProvisioningService} 保持一致。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 口令与改密时间的权威用户事实。 */
    private final AppUserRepository appUserRepository;
    /** 改密撤销用户全部项目会话，不因调用项目缩小安全收束范围。 */
    private final AppRefreshTokenRepository refreshTokenRepository;
    /** 沿用既有口令匹配和编码策略，不保存明文。 */
    private final PasswordEncoder passwordEncoder;
    /** 在业务原事务建立用户表所需的可信租户 RLS 范围。 */
    private final TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    /** ADR 0064 决策 2/4 要求在原改密事务持有可信项目写许可。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /**
     * 构造改密与全会话撤销的原子编排。
     * @param appUserRepository 用户口令仓储
     * @param refreshTokenRepository 全会话撤销仓储
     * @param passwordEncoder 口令匹配与编码策略
     * @param tenantTransactionLocalRlsScope 原事务租户范围入口
     * @param projectWriteGuard 项目生命周期写许可
     */
    public AppPasswordService(AppUserRepository appUserRepository,
                              AppRefreshTokenRepository refreshTokenRepository,
                              PasswordEncoder passwordEncoder,
                              TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope,
                              AppProjectWriteGuard projectWriteGuard) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantTransactionLocalRlsScope = tenantTransactionLocalRlsScope;
        this.projectWriteGuard = projectWriteGuard;
    }

    /**
     * 修改自己的口令并撤销全部会话。
     *
     * @param appUserId   终端用户 ID（应用 JWT 主体）
     * @param tenantId    归属租户（应用 JWT 租户声明）
     * @param projectId   当前项目（已验证 应用 JWT 项目声明），只限定改密入口资格
     * @param oldPassword 原口令
     * @param newPassword 新口令
     * @throws BusinessException 项目只读（60022）、访问无效（60009）、原口令错（60008）或新口令过短（10001）
     */
    @Transactional
    public void changePassword(UUID appUserId, UUID tenantId, UUID projectId,
                               String oldPassword, String newPassword) {
        // ADR 0064 决策 2/4：原事务先持项目 SHARE 许可，冻结拒绝不得改口令或撤销任何项目会话。
        projectWriteGuard.requireWritable(tenantId, projectId);
        tenantTransactionLocalRlsScope.establish(tenantId);

        // ADR0097：改密与所有项目签发/轮换共用用户锁，锁后验密并在原事务撤销全部会话。
        AppUser user = appUserRepository.lockByIdAndTenant(tenantId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));

        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new BusinessException(EndUserErrorCode.END_USER_PASSWORD_INCORRECT);
        }

        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "新口令长度不足 " + MIN_PASSWORD_LENGTH + " 位");
        }

        String newHash = passwordEncoder.encode(newPassword);
        appUserRepository.updatePassword(tenantId, appUserId, newHash, Instant.now());
        refreshTokenRepository.revokeAllForUser(appUserId, Instant.now());
    }

}
