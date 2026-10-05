package com.things.link.assistant.infrastructure.transport;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 包内有界解析器；调用者停止等待后仍由工作线程持槽，避免遗留无界解析任务。 */
final class BoundedAnalysisResolver {
    /** 解析单个固定主机，不接收凭据或业务对象。 */
    @FunctionalInterface interface Lookup { InetAddress resolve(String host) throws Exception; }
    private final Semaphore slots;
    private final Lookup lookup;

    /**
     * 创建独立有界解析资源，生产实例由单次客户端固定装配。
     * @param capacity 允许同时存在的实际解析任务数
     * @param lookup 包内解析操作；生产只调用系统主机解析
     */
    BoundedAnalysisResolver(int capacity, Lookup lookup) {
        if (capacity < 1 || lookup == null) throw failure();
        this.slots = new Semaphore(capacity);
        this.lookup = lookup;
    }

    /**
     * 在剩余预算内等候结果，启动耗时也扣除；没有排队、地址回退或自动重试。
     * @param host 固定内部主机名
     * @param remainingNanos 原连接阶段剩余纳秒
     * @return 系统解析的第一个地址
     * @throws Exception 预算耗尽、中断或解析失败；底层解析原因不跨边界传播
     */
    InetAddress resolve(String host, long remainingNanos) throws Exception {
        long started = System.nanoTime();
        if (remainingNanos <= 0 || !slots.tryAcquire()) throw failure();
        var resolved = new CompletableFuture<InetAddress>();
        try {
            Thread worker = new Thread(() -> {
                try { resolved.complete(lookup.resolve(host)); }
                catch (Exception ignored) { resolved.completeExceptionally(failure()); }
                finally { slots.release(); }
            }, "agent-analysis-dns");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException failure) {
            slots.release();
            throw failure;
        }
        long remaining = remainingNanos - (System.nanoTime() - started);
        if (remaining <= 0) throw failure();
        return resolved.get(remaining, TimeUnit.NANOSECONDS);
    }

    /** 统一解析失败，不携带主机、地址或原异常。 */
    private static IllegalStateException failure() { return new IllegalStateException("INTERNAL_ANALYSIS_RESOLUTION_FAILED"); }
}
