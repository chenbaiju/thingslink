package com.things.link.iam.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号与成员身份的仓储契约。
 *
 * <p>接口定义在领域层、实现在 {@code infrastructure} 层，依赖方向由此反转 ——
 * 领域层不依赖任何持久化技术（架构文档 10.3）。
 *
 * <p><b>本仓储访问的两张表豁免了 RLS</b>（account、tenant_member），因为认证发生在
 * 租户身份确立之前。这意味着这里**没有数据库层的兜底**：除登录流程外，任何按租户
 * 范围的查询都必须在方法签名里显式带上 tenantId，不能依赖会话上下文。
 */
public interface AccountRepository {

    /**
     * 按邮箱查账号，用于登录。
     *
     * <p>邮箱比较**不区分大小写**，与迁移中 {@code lower(email)} 的唯一索引一致 ——
     * 否则用户用 {@code Foo@x.com} 注册、用 {@code foo@x.com} 登录会查不到，
     * 而唯一索引又不允许他重新注册，等于账号被锁死。
     *
     * @param email 邮箱
     * @return 账号；不存在或已软删除时为空
     */
    Optional<Account> findByEmail(String email);

    /**
     * 按 ID 查账号，用于已认证请求还原当前用户。
     *
     * @param id 账号 ID
     * @return 账号；不存在或已软删除时为空
     */
    Optional<Account> findById(UUID id);

    /** 原事务持有账号共享锁；阻止禁用/删除越过责任身份复验。 */
    Optional<Account> lockById(UUID id);

    /**
     * 批量按 ID 查账号。
     *
     * <p>用于「已经拿到一批 accountId，要显示他们是谁」的场景（项目成员列表）。
     * 逐个调 {@link #findById(UUID)} 就是 N+1：十个成员发十一次查询。
     *
     * <p>空集合直接返回空列表，<b>不发查询</b> —— 拼出来的
     * {@code IN ()} 在 PostgreSQL 里是语法错误。
     *
     * @param ids 账号 ID 集合
     * @return 查到的账号；已软删除的不会出现，因此可能少于入参数量
     */
    List<Account> findAllById(java.util.Collection<UUID> ids);

    /**
     * 查某个账号的全部有效租户成员身份。
     *
     * @param accountId 账号 ID
     * @return 成员身份列表，可能为空
     */
    List<TenantMembership> findMembershipsByAccount(UUID accountId);

    /**
     * 记录登录时间。
     *
     * <p>与登录校验分开，且失败不应阻断登录 —— 这是审计信息，
     * 不是认证的必要条件。
     *
     * @param accountId 账号 ID
     * @param at        登录时间
     */
    void recordLogin(UUID accountId, Instant at);

    /**
     * 创建账号。
     *
     * <p><b>邮箱唯一性由数据库唯一索引仲裁，不要先查后插。</b>「先 findByEmail 判断
     * 不存在、再 insert」在并发下必然出现两个请求都查到不存在、然后一个成功一个
     * 报错的情况 —— 而那个报错会是未经处理的 500。让唯一索引来判，把
     * {@code DuplicateKeyException} 翻译成业务错误码，是唯一正确的做法
     * （与幂等键那套是同一个道理）。
     *
     * @param account 待创建的账号，{@code passwordHash} 必须已经是哈希
     * @throws org.springframework.dao.DuplicateKeyException 邮箱已被占用
     */
    void create(Account account);

    /**
     * 建立账号与租户的成员关系。
     *
     * @param membershipId 成员记录 ID
     * @param tenantId     租户 ID
     * @param accountId    账号 ID
     */
    void addMembership(UUID membershipId, UUID tenantId, UUID accountId);

    /**
     * 记一次登录失败，并在达到阈值时临时锁定账号。
     *
     * <p><b>累加与判阈值必须在同一条 SQL 里完成</b>，不能「先查次数、再判断、再更新」：
     * 并发的爆破请求会同时读到同一个次数，各自判断未达阈值，于是锁定永远不触发。
     * 这类竞态在本机手工测试中几乎不可能复现，但爆破场景本身就是高并发的。
     *
     * <p>已在锁定期内的账号<b>不再延长锁定</b>。否则攻击者只要持续发请求就能让
     * 目标账号永远登不进去 —— 把一个防爆破措施变成一个免费的拒绝服务通道。
     *
     * @param accountId    账号 ID
     * @param maxAttempts  触发锁定的连续失败次数
     * @param lockDuration 锁定时长
     * @param now          当前时刻
     */
    void recordFailedLogin(UUID accountId, int maxAttempts, Duration lockDuration, Instant now);

    /**
     * 清零连续失败计数并解除临时锁定。登录成功时调用。
     *
     * @param accountId 账号 ID
     */
    void resetFailedLogin(UUID accountId);

    /**
     * 把账号邮箱标记为已验证。
     *
     * <p>比对的是<b>令牌签发时的目标邮箱</b>而不是账号当前邮箱。用户在等待验证期间
     * 又改了邮箱的话，旧链接不该把新邮箱标记为已验证。
     *
     * <p>重复调用是空操作：{@code email_verified_at IS NULL} 保证首次验证的时刻
     * 不会被后续点击刷新，否则事后排查「何时验证的」会看到错的答案。
     *
     * @param accountId  账号 ID
     * @param email      令牌签发时要证实的邮箱，不区分大小写
     * @param verifiedAt 验证时刻
     * @return 本次真的更新了返回 true；账号不存在、邮箱已变、或早已验证过返回 false
     */
    boolean markEmailVerified(UUID accountId, String email, Instant verifiedAt);

    /**
     * 替换账号口令。
     *
     * <p>顺带清零连续失败计数与临时锁定：用户刚刚证明了自己控制着注册邮箱并设了新口令，
     * 继续把他锁在门外没有任何意义 —— 那只会让「重置密码」这条自助通道看起来失效。
     *
     * @param accountId    账号 ID
     * @param passwordHash 带算法前缀的新口令哈希（ADR 0009），<b>必须已经是哈希</b>
     */
    void updatePassword(UUID accountId, String passwordHash);

}
