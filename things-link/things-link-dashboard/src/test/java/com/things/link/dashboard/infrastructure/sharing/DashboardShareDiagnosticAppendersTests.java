package com.things.link.dashboard.infrastructure.sharing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 诊断队列和磁盘故障必须可观测，不能回滚匿名业务或无限等待日志工作线程。 */
class DashboardShareDiagnosticAppendersTests {
    /** 实际文件在隔离目录，不触碰项目日志。 */ @TempDir Path directory;

    /** 消费者门闩阻塞时第三个排队事件被丢弃，并不把生产者挂起等磁盘。 */
    @Test
    void fullQueueDropsWithCounterAndShutdownIsBounded() throws Exception {
        LoggerContext context = context();
        var metrics = new SimpleMeterRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AppenderBase<ILoggingEvent> sink = new AppenderBase<>() {
            /** 用受控门闩代替不稳定的慢磁盘sleep。 */
            @Override protected void append(ILoggingEvent event) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        };
        sink.setContext(context); sink.setName("blocked-file"); sink.start();
        var async = new DashboardShareDiagnosticAppenders.BoundedAsync(metrics);
        async.setContext(context); async.setName("bounded-test"); async.setQueueSize(2);
        async.setNeverBlock(true); async.setDiscardingThreshold(0); async.setMaxFlushTime(100);
        async.addAppender(sink); async.start();
        try {
            async.doAppend(event(context));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            async.doAppend(event(context)); async.doAppend(event(context)); async.doAppend(event(context));
            assertThat(metrics.get("dashboard.share.security.events.dropped").tag("reason", "queue_full").counter().count()).isEqualTo(1);
            long start = System.nanoTime(); async.stop();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
        } finally { release.countDown(); async.stop(); context.stop(); }
    }

    /** 写入异常和其后停止的appender均计disk_failure，绝不输出底层异常作为安全事件正文。 */
    @Test
    void diskFailureAndSubsequentDroppedEventsAreCounted() {
        LoggerContext context = context();
        var metrics = new SimpleMeterRegistry();
        var file = new DashboardShareDiagnosticAppenders.ObservedRollingFile(metrics);
        file.setContext(context); file.setName("disk-failure-test"); file.setFile(directory.resolve("security.log").toString());
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context); encoder.setPattern("%msg%n"); encoder.start(); file.setEncoder(encoder);
        var rolling = new SizeAndTimeBasedRollingPolicy<ILoggingEvent>();
        rolling.setContext(context); rolling.setParent(file);
        rolling.setFileNamePattern(directory.resolve("security-%d{yyyy-MM-dd}.%i.log").toString());
        rolling.setMaxFileSize(new FileSize(1024)); rolling.setMaxHistory(1); rolling.start();
        file.setRollingPolicy(rolling); file.start();
        file.setOutputStream(new OutputStream() {
            /** 明确模拟真实OutputStream写入异常，不通过服务mock直接增加计数。 */
            @Override public void write(int value) throws IOException { throw new IOException("private disk error"); }
        });
        try {
            file.doAppend(event(context)); file.doAppend(event(context));
            assertThat(metrics.get("dashboard.share.security.events.dropped").tag("reason", "disk_failure").counter().count()).isEqualTo(2);
        } finally { file.stop(); context.stop(); }
    }

    /** 每个appender测试拥有独立context与MDC适配，不重置全局日志。 */
    private static LoggerContext context() {
        LoggerContext context = new LoggerContext(); context.setMDCAdapter(new LogbackMDCAdapter()); context.start(); return context;
    }
    /** 仅固定安全文本，不混入请求或凭据夹具。 */
    private static LoggingEvent event(LoggerContext context) {
        return new LoggingEvent("test", context.getLogger("share.security"), Level.INFO, "{}", null, null);
    }
}
