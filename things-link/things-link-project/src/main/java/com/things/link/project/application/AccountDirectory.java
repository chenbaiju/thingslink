package com.things.link.project.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 账号目录：project 模块查询账号身份的<b>出口端口</b>。
 *
 * <h2>为什么要有这个接口，而不是直接调 iam</h2>
 * 邀请成员必须按邮箱找到账号，成员列表必须显示邮箱与显示名 —— 这些都是 iam 的数据。
 * 但依赖方向已经被占了：<b>iam 依赖 project</b>（菜单要按项目角色过滤），
 * project 再反过来依赖 iam 就是循环依赖，Maven 直接构建不过。
 *
 * <p>于是做依赖反转：<b>接口由需要它的一方（project）定义，由拥有数据的一方（iam）实现</b>。
 * iam → project.application 是既有的、允许的方向（架构文档 10.4 规则 2）。
 * project 只认识这个接口，不知道实现在哪。
 *
 * <h2>为什么不把账号表的查询抄一份到 project</h2>
 * 那样「邮箱大小写不敏感」「软删除的账号不算数」这两条规则就会有两个副本，
 * 而它们<b>只在其中一处被改</b>的那天不会有任何症状 —— 只会表现为「这个邮箱明明
 * 注册过，邀请却说找不到」。查询逻辑必须只有一份，在 iam 那边。
 * 项目恢复只从本端口取得“账号当前是否ACTIVE”的布尔判断，不把口令哈希、锁定细节或IAM枚举
 * 暴露给project模块。
 */
public interface AccountDirectory {

    /**
     * 按邮箱查账号。
     *
     * <p>邮箱比较<b>不区分大小写</b>，与账号表 {@code lower(email)} 的唯一索引一致。
     * 区分大小写的话，用 {@code Foo@x.com} 注册的人永远无法被 {@code foo@x.com} 邀请到。
     *
     * @param email 邮箱
     * @return 账号；不存在或已软删除时为空
     */
    Optional<AccountRef> findByEmail(String email);

    /**
     * 批量按 ID 查账号。
     *
     * <p>批量而不是逐个查：成员列表拿到 N 个 accountId 后若逐个回库，就是典型的
     * N+1 —— 十个成员的项目发十一次查询。这个接口天生是批量场景，
     * 单条版本不提供，免得调用方在循环里用它。
     *
     * @param ids 账号 ID 集合，可为空集合
     * @return 查到的账号；<b>顺序与入参无关</b>，且已删除的账号不会出现，
     *         因此返回的数量可能少于入参
     */
    List<AccountRef> findByIds(Collection<UUID> ids);

    /**
     * 判断账号是否仍可认证；项目恢复锁后必须重新读取，不能仅相信JWT签发时状态。
     * @param accountId 已认证请求声明中的账号
     * @return 账号存在、未软删且状态为ACTIVE时为true
     */
    boolean isActive(UUID accountId);

    /** 已确权后台责任身份在原写事务中锁定ACTIVE状态，持锁到动作/受理提交。 */
    boolean lockActive(UUID accountId);

    /** 原写事务锁定账号，并要求仍ACTIVE且邮箱已验证；不能以当前JWT替代接受时事实。 */
    boolean lockVerifiedActive(UUID accountId);

    /** 账号级邀请收件箱只向当前仍有效且邮箱已验证的账号开放。 */
    boolean isVerifiedActive(UUID accountId);

}
