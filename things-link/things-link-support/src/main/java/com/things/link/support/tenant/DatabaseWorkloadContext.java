package com.things.link.support.tenant;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 把数据库工作负载绑定到当前执行线程。
 *
 * <p>不使用 InheritableThreadLocal：线程池会复用线程，继承值既不能可靠传播，也可能把上一项工作的 DATA 路由泄漏给下一项。
 * 异步入口必须自行重新建立范围。</p>
 */
public final class DatabaseWorkloadContext {
    /** 当前线程的显式工作负载；空表示 fail-safe 的 CONTROL。 */
    private static final ThreadLocal<DatabaseWorkload> CURRENT = new ThreadLocal<>();
    /** 同一调用链跨故障域切换次数；低基数累计值供 D-048 告警。 */
    private static final AtomicLong ROUTE_CONFLICTS = new AtomicLong();

    /** 工具类不允许实例化。 */
    private DatabaseWorkloadContext() {
    }

    /**
     * 返回路由键；未分类代码必须落到 CONTROL，避免后台规则遗漏反过来挤占控制面保留容量。
     *
     * @return 当前工作负载
     */
    public static DatabaseWorkload current() {
        return CURRENT.get() == null ? DatabaseWorkload.CONTROL : CURRENT.get();
    }

    /**
     * 进入显式工作负载范围。
     *
     * @param workload 目标工作负载
     * @return 关闭时恢复上一层范围的句柄
     */
    public static Scope enter(DatabaseWorkload workload) {
        DatabaseWorkload previous = CURRENT.get();
        if (previous != null && previous != workload) {
            ROUTE_CONFLICTS.incrementAndGet();
            throw new IllegalStateException("同一调用链禁止切换数据库连接池故障域");
        }
        // 未显式建立路由的事务已按默认 CONTROL 借出连接；此时再进入 DATA 只会
        // 改变 ThreadLocal，不会更换事务连接。必须显式拒绝，否则指标显示 DATA 而 SQL 实际仍在 CONTROL。
        if (previous == null && workload == DatabaseWorkload.DATA
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            ROUTE_CONFLICTS.incrementAndGet();
            throw new IllegalStateException("事务已在 CONTROL 连接上开始，禁止途中切换到 DATA");
        }
        CURRENT.set(workload);
        return new Scope(previous);
    }

    /** @return 进程启动后检测到的跨故障域切换累计次数 */
    public static long routeConflicts() {
        return ROUTE_CONFLICTS.get();
    }

    /** 恢复上一层工作负载，保证异常路径也不会污染线程池。 */
    public static final class Scope implements AutoCloseable {
        /** 进入本层前的值。 */
        private final DatabaseWorkload previous;
        /** 防止重复 close 破坏外层范围。 */
        private boolean closed;

        /** @param previous 上一层工作负载 */
        private Scope(DatabaseWorkload previous) {
            this.previous = previous;
        }

        /** 恢复或清除线程变量。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
