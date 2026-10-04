package com.things.link.iam.application;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.IamErrorCode;
import com.things.link.iam.domain.TenantMembership;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 还原当前登录用户。
 *
 * <h2>为什么不直接用令牌里的声明</h2>
 * 令牌里有 accountId、tenantId，但**没有邮箱与显示名** —— 它们是个人信息，
 * 而 JWT 只是 Base64 编码，放进去等于对任何持有令牌的人公开
 * （见 {@code JwtTokenIssuer} 的说明）。所以要回库取。
 *
 * <p>顺带这一步还会**重新校验账号状态**：令牌签发后无法撤销，账号在有效期内被
 * 停用的话，只有回库才能发现。这是无状态令牌方案里为数不多的补救点。
 */
@Service
public class CurrentUserService {

    private final AccountRepository accountRepository;

    public CurrentUserService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * 按令牌中的身份还原当前用户。
     *
     * @param accountId 令牌 subject 中的账号 ID
     * @param tenantId  令牌中的租户 ID
     * @return 当前用户
     * @throws BusinessException 账号不存在、已停用，或成员关系已失效
     */
    @Transactional(readOnly = true)
    public CurrentUser resolve(UUID accountId, UUID tenantId) {
        Account account = accountRepository.findById(accountId)
                // 账号在令牌有效期内被删除。令牌本身仍是合法签名，
                // 所以必须在这里拦住，否则等于账号删了还能继续用
                .orElseThrow(() -> new BusinessException(IamErrorCode.INVALID_TOKEN));

        if (!account.canAuthenticate()) {
            // 账号在令牌有效期内被停用，同上
            throw new BusinessException(IamErrorCode.ACCOUNT_DISABLED);
        }

        TenantMembership membership = accountRepository.findMembershipsByAccount(accountId).stream()
                .filter(m -> m.tenantId().equals(tenantId))
                .filter(TenantMembership::isActive)
                // 成员关系在令牌有效期内被移除或停用 —— 用户被踢出租户后
                // 不应再能访问该租户的数据
                .findFirst()
                .orElseThrow(() -> new BusinessException(IamErrorCode.INVALID_TOKEN));

        return new CurrentUser(account.id(), account.email(), account.displayName(),
                membership.tenantId());
    }

}
