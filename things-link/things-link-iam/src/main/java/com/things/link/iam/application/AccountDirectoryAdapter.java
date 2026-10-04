package com.things.link.iam.application;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.AccountRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AccountDirectory} 的实现：把 project 的账号查询请求转给 iam 的仓储。
 *
 * <h2>依赖反转的落点</h2>
 * 接口在 {@code project.application}，实现在这里。方向是
 * <b>iam → project.application</b>，与既有的 {@code MenuController → ProjectService}
 * 一致，不产生循环（架构文档 10.4 规则 2）。
 *
 * <p>Spring 在启动时把这个 Bean 注入 project 的成员服务 —— 两个模块在编译期
 * 谁也不认识谁的实现，只在运行期由容器接上。
 *
 * <h2>为什么不在适配器发明授权规则</h2>
 * 一旦在这里加上「只返回同租户的账号」之类的规则，规则就藏在了两个模块的接缝里。
 * 成员授权仍留在project；恢复所需账号有效性则直接复用{@link Account#canAuthenticate()}，
 * 适配器只把IAM的权威事实收窄为端口约定的布尔值。
 */
@Service
public class AccountDirectoryAdapter implements AccountDirectory {

    private final AccountRepository accountRepository;

    public AccountDirectoryAdapter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRef> findByEmail(String email) {
        return accountRepository.findByEmail(email).map(AccountDirectoryAdapter::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountRef> findByIds(Collection<UUID> ids) {
        return accountRepository.findAllById(ids).stream()
                .map(AccountDirectoryAdapter::toRef)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID accountId) {
        // findById已排除软删账号；状态判断复用Account领域规则，数据库故障不降级成false。
        return accountRepository.findById(accountId)
                .map(Account::canAuthenticate)
                .orElse(false);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean lockActive(UUID accountId) {
        return accountRepository.lockById(accountId).map(Account::canAuthenticate).orElse(false);
    }

    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean lockVerifiedActive(UUID accountId) {
        return accountRepository.lockById(accountId)
                .map(account -> account.canAuthenticate() && account.isEmailVerified()).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVerifiedActive(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(account -> account.canAuthenticate() && account.isEmailVerified()).orElse(false);
    }

    /**
     * 转成端口的返回类型。
     *
     * <p>这一步<b>不是多余的样板</b>：它是口令哈希与风控状态的截止线。
     * 直接把 {@link Account} 交出去的话，{@code passwordHash} 就进入了
     * 项目成员列表的调用链，离被序列化进 HTTP 响应只差一个手滑。
     *
     * @param account iam 的账号
     * @return 最小画像
     */
    private static AccountRef toRef(Account account) {
        return new AccountRef(account.id(), account.email(), account.displayName());
    }

}
