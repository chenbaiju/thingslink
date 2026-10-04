package com.things.link.shared.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户上下文：把当前请求的 {@link TenantScope} 绑定到线程。
 *
 * <p>架构文档第 7 节：<b>应用层通过 TenantContext 强制注入范围，PostgreSQL RLS
 * 作为第二道防线。</b> 两道防线缺一不可 —— 应用层漏一个 where 条件就是跨租户
 * 数据泄露，而这类 bug 功能测试完全发现不了（单租户下一切正常）。
 *
 * <h2>为什么用 ThreadLocal</h2>
 * 租户范围要渗透到每一次数据库访问，逐层传参会污染所有方法签名，且一旦某处忘了
 * 传就静默失效。ThreadLocal 的代价是必须保证清理，见下。
 *
 * <h2>必须清理</h2>
 * Web 容器复用线程。不清理的话，下一个请求会继承上一个请求的租户范围 ——
 * 这是<b>最严重的一类 bug</b>：偶发、只在高并发下出现、且后果是跨租户数据泄露。
 * 清理由 support 模块的过滤器在 finally 块中保证，业务代码不要手工调用
 * {@link #clear()}。
 *
 * <h2>虚拟线程与异步</h2>
 * ThreadLocal 不会自动传播到新线程。异步任务（{@code @Async}、Kafka 消费、
 * 定时任务）必须显式传递 scope —— 那些场景下没有「当前请求」，租户范围要从消息
 * 信封或任务定义里取（架构文档第 5 节的消息信封已包含 tenantId 与 projectId）。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
        // 工具类，不允许实例化
    }

    /**
     * 绑定租户范围到当前线程。仅由框架层（过滤器、消息消费者）调用。
     *
     * <p>同时把租户/项目镜像到 {@link RlsScopeContext}：那是 {@code TenantAwareDataSource}
     * 驱动 RLS 的输入。镜像在这里做，是为了让「控制台过滤器只写 {@link TenantContext}」
     * 与「既有测试只调 {@link TenantContext#set}」这两类既有代码零改动地继续喂对数据源。
     *
     * @param scope 租户范围
     */
    public static void set(TenantScope scope) {
        CURRENT.set(scope);
        RlsScopeContext.set(new RlsScope(scope.tenantId(), scope.projectId()));
    }

    /**
     * 清除当前线程的绑定。
     *
     * <p>必须在 finally 块中调用，否则线程复用会导致跨租户数据泄露。
     * 同时清除 {@link RlsScopeContext}，二者一起建立、一起失效。
     */
    public static void clear() {
        CURRENT.remove();
        RlsScopeContext.clear();
    }

    /**
     * 取当前租户范围，可能不存在（未认证的公开接口）。
     *
     * @return 当前租户范围
     */
    public static Optional<TenantScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 取当前租户范围，不存在则抛异常。
     *
     * <p>业务代码应当用这个而不是 {@link #current()}：需要租户范围却拿不到时，
     * 正确的行为是<b>立刻失败</b>，而不是继续执行一个没有租户过滤的查询。
     *
     * @return 当前租户范围
     * @throws IllegalStateException 当前线程没有绑定租户范围
     */
    public static TenantScope require() {
        TenantScope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException(
                    "当前线程没有租户上下文。业务数据访问必须在已认证的请求内进行；"
                            + "异步任务需从消息信封或任务定义显式恢复租户范围。");
        }
        return scope;
    }

    /**
     * 取当前项目 ID，未选定项目时抛异常。
     *
     * <p>项目级数据接口用这个。让「忘了选项目」在入口处就失败，
     * 而不是变成一个跨项目的查询。
     *
     * @return 当前项目 ID
     * @throws IllegalStateException 没有租户上下文，或上下文中未选定项目
     */
    public static UUID requireProjectId() {
        UUID projectId = require().projectId();
        if (projectId == null) {
            throw new IllegalStateException("当前请求未选定项目，无法访问项目级数据");
        }
        return projectId;
    }

}
