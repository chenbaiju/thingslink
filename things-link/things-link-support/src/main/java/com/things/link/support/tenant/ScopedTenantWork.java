package com.things.link.support.tenant;

import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import java.util.Objects;
import java.util.function.Supplier;

/** 框架层显式异步范围；只接受已认证入口或受限数据库领取返回的可信身份。 */
public final class ScopedTenantWork {
    /** 不允许实例化工具类。 */
    private ScopedTenantWork() { }
    /** 在当前线程临时绑定可信范围，异常也恢复之前的两种上下文。 */
    public static <T> T call(TenantScope scope, Supplier<T> action) {
        Objects.requireNonNull(scope, "scope");
        TenantScope previous = TenantContext.current().orElse(null);
        RlsScope previousRls = RlsScopeContext.current().orElse(null);
        try {
            TenantContext.set(scope);
            return action.get();
        } finally {
            TenantContext.clear();
            if (previous != null) TenantContext.set(previous);
            if (previousRls != null) RlsScopeContext.set(previousRls);
            else RlsScopeContext.clear();
        }
    }
    /** 无返回值任务与有返回值任务共享同一恢复语义。 */
    public static void run(TenantScope scope, Runnable action) {
        call(scope, () -> { action.run(); return null; });
    }
}
