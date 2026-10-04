package com.things.link.shared.tenant;

import java.util.Optional;

/**
 * RLS 隔离范围上下文：把 {@link RlsScope} 绑定到线程，供 {@code TenantAwareDataSource}
 * 在借出连接时写入 {@code app.tenant_id} / {@code app.project_id} 会话变量。
 *
 * <p>它是 {@link TenantContext} 的**数据面投影**：控制台过滤器经 {@link TenantContext#set}
 * 镜像进来（见该方法），App 过滤器直接写这里。两类请求最终都只靠这一处驱动数据库的
 * RLS，避免了「数据源该读哪个上下文」的二义性。
 *
 * <h2>必须清理</h2>
 * 与 {@link TenantContext} 同构的 ThreadLocal 复用风险：不清除会让下一个请求继承上一个
 * 请求的隔离范围。清理仍然由 support 的 {@code TenantContextFilter} 在最外层 finally
 * 里保证 —— 它同时清 {@link TenantContext} 与本上下文。
 */
public final class RlsScopeContext {

    private static final ThreadLocal<RlsScope> CURRENT = new ThreadLocal<>();

    private RlsScopeContext() {
        // 工具类，不允许实例化
    }

    /**
     * 绑定隔离范围到当前线程。
     *
     * @param scope 隔离范围
     */
    public static void set(RlsScope scope) {
        CURRENT.set(scope);
    }

    /**
     * 清除当前线程的绑定。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 取当前隔离范围，可能不存在（未认证的公开接口）。
     *
     * @return 当前隔离范围
     */
    public static Optional<RlsScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

}
