package com.things.link.dashboard.infrastructure.sharing;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;

/** ADR0101诊断丢弃可观测，不把日志失败或队列背压传播到业务读取。 */
public final class DashboardShareDiagnosticAppenders {
    /** 仅容纳无实例状态的类型集合。 */
    private DashboardShareDiagnosticAppenders() { }

    /** 有界异步队列满时丢弃一条并计数；不采用默认提前丢弃INFO的阈值。 */
    public static final class BoundedAsync extends AsyncAppender {
        /** 只含静态reason标签的丢弃计数器。 */ private final MeterRegistry metrics;
        /** 通过显式构造确保文件与队列共享应用指标注册表。 */
        public BoundedAsync(MeterRegistry metrics) { this.metrics = metrics; }
        /** 串行生产者先判余量，消费只会增加余量，不存在未计数offer失败竞争。 */
        @Override protected synchronized void append(ILoggingEvent event) {
            if (getRemainingCapacity() == 0) {
                metrics.counter("dashboard.share.security.events.dropped", "reason", "queue_full").increment();
                return;
            }
            super.append(event);
        }
        /** 最长5秒排空后仍未写入的事件也留下低基数丢弃事实。 */
        @Override public void stop() {
            if (!isStarted()) return;
            super.stop();
            int pending = getNumberOfElementsInQueue();
            if (pending > 0) metrics.counter("dashboard.share.security.events.dropped", "reason", "shutdown_timeout").increment(pending);
        }
    }

    /** 首次I/O失败及之后因停止而丢失的事件均记录，不能靠空日志冒称完整诊断。 */
    public static final class ObservedRollingFile extends RollingFileAppender<ILoggingEvent> {
        /** 固定原因计数器，不包含路径、凭据或原异常。 */ private final MeterRegistry metrics;
        /** 程序化装配使用同一应用指标注册表。 */
        public ObservedRollingFile(MeterRegistry metrics) { this.metrics = metrics; }
        /** OutputStreamAppender会吞I/O并停止，先计数再交由其处理。 */
        @Override protected void writeOut(ILoggingEvent event) throws IOException {
            try { super.writeOut(event); }
            catch (IOException failure) {
                metrics.counter("dashboard.share.security.events.dropped", "reason", "disk_failure").increment();
                throw failure;
            }
        }
        /** 已停止文件appender不能默默丢弃后续匿名事件。 */
        @Override public void doAppend(ILoggingEvent event) {
            if (!isStarted()) {
                metrics.counter("dashboard.share.security.events.dropped", "reason", "disk_failure").increment();
                return;
            }
            super.doAppend(event);
        }
    }
}
