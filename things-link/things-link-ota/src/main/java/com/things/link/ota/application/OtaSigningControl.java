package com.things.link.ota.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 单调签名预算和可注销物理取消信号；调度者负责定期check，不创建隐式后台线程。 */
public final class OtaSigningControl {
    /** 从构造时开始计时，包含前置调用耗时。 */
    private final long started = System.nanoTime();
    /** 最大90秒的固定纳秒预算。 */
    private final long budget;
    /** 外部失租判断必须非阻塞。 */
    private final BooleanSupplier external;
    /** 第一个停止原因不可被后续异常覆盖。 */
    private final AtomicReference<Reason> stopped = new AtomicReference<>();
    /** 只包含尚未注销的物理调用取消钩子。 */
    private final Set<Hook> hooks = ConcurrentHashMap.newKeySet();
    /** 构造有界预算；不允许无限等待或超过本片90秒上限。 */
    public OtaSigningControl(Duration timeout, BooleanSupplier cancelled) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(90)) > 0)
            throw new IllegalArgumentException("签名操作预算不合法");
        budget = timeout.toNanos();
        external = Objects.requireNonNull(cancelled, "cancelled");
    }
    /** 返回单调剩余纳秒，供应商必须据此设置实际网络预算。 */
    public long remainingNanos() { return Math.max(0, budget - (System.nanoTime() - started)); }
    /** 判断自然预算耗尽，不意味着物理调用已结束。 */
    public boolean timedOut() { return remainingNanos() == 0; }
    /** 检查外部信号和截止，触发真实取消钩子并返回是否失权。 */
    public boolean cancelled() {
        observe();
        return stopped.get() != null;
    }
    /** 主动停止所有当前钩子，只保存第一个固定原因。 */
    public void cancel() { stop(Reason.CANCELLED); }
    /** 适配器和调度器共同使用的检查入口，固定错误不含供应商信息。 */
    public void check() {
        observe();
        Reason reason = stopped.get();
        if (reason != null) throw new Failure(reason);
    }
    /** 注册非阻塞取消钩子；若已停止立即调用，适配器finally必须注销。 */
    public Registration onCancel(Runnable action) {
        Hook hook = new Hook(Objects.requireNonNull(action, "action"));
        hooks.add(hook);
        observe();
        if (stopped.get() != null) hook.fire();
        return hook;
    }
    /** 首先观察已经登记的首因，再检查单调预算及外部失败。 */
    private void observe() {
        if (stopped.get() != null) return;
        if (timedOut()) { stop(Reason.TIMEOUT); return; }
        try { if (external.getAsBoolean()) stop(Reason.CANCELLED); }
        catch (RuntimeException failure) { stop(Reason.CANCELLED); }
    }
    /** 一个钩子失败不能阻止其他实际调用收到取消；不暴露其异常正文。 */
    private void stop(Reason reason) {
        if (stopped.compareAndSet(null, reason)) hooks.forEach(Hook::fire);
    }
    /** 无检查异常的注销句柄，仅表示不再接收取消信号。 */
    public interface Registration extends AutoCloseable {
        /** 适配器已完成实际调用后移除注册，不伪造完成证明。 */
        @Override void close();
    }
    /** 单个钩子最多触发一次，注销与触发竞争也不会重复执行。 */
    private final class Hook implements Registration {
        /** 必须为非阻塞的实际调用取消动作。 */
        private final Runnable action;
        /** 触发或注销后永久关闭。 */
        private final AtomicBoolean done = new AtomicBoolean();
        /** 保存供应商明确提供的取消动作。 */
        private Hook(Runnable action) { this.action = action; }
        /** 独立执行取消，异常不阻断同组其他钩子。 */
        private void fire() {
            if (!done.compareAndSet(false, true)) return;
            hooks.remove(this);
            try { action.run(); } catch (RuntimeException ignored) { /* 首因已固定，不能传播供应商异常。 */ }
        }
        /** 注销只移除自身，不取消其他调用。 */
        @Override public void close() { done.set(true); hooks.remove(this); }
    }
    /** 固定停止原因，不包含物理完成含义。 */
    public enum Reason {
        /** 总预算耗尽。 */ TIMEOUT,
        /** 外部取消、失租或取消源失败。 */ CANCELLED
    }
    /** 只包含固定首因的异常。 */
    public static final class Failure extends RuntimeException {
        /** 稳定序列化标识。 */
        private static final long serialVersionUID = 1L;
        /** 首个停止原因。 */
        private final Reason reason;
        /** 固定消息，不保存供应商异常链。 */
        private Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        /** 返回首个固定原因。 */
        public Reason reason() { return reason; }
    }
}
