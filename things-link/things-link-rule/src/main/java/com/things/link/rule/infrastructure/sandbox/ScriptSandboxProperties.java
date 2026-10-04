package com.things.link.rule.infrastructure.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * S8 独立 Worker JVM 脚本沙箱的全部硬边界。
 *
 * @param maxCpuTime Worker 就绪后单次 guest CPU 时间
 * @param maxWallTime Worker 就绪后单次 guest 墙钟时间
 * @param maxStartupTime Worker JVM 与 GraalJS Context 启动上限
 * @param maxWorkerHeapMemory 单次执行 Worker Java 堆上限
 * @param maxWorkerMetaspace 单次执行 Worker 元空间上限
 * @param maxSourceBytes 脚本源码 UTF-8 上限
 * @param maxInputBytes 输入 JSON UTF-8 上限
 * @param maxOutputBytes 返回 JSON 上限
 * @param maxErrorBytes Worker stderr 捕获上限
 * @param poolSize 独立执行线程数
 * @param queueCapacity 独立有界等待队列容量
 */
@ConfigurationProperties("things-link.rule.sandbox")
public record ScriptSandboxProperties(
        @DefaultValue("50ms") Duration maxCpuTime,
        @DefaultValue("2s") Duration maxWallTime,
        @DefaultValue("5s") Duration maxStartupTime,
        @DefaultValue("64MB") DataSize maxWorkerHeapMemory,
        @DefaultValue("64MB") DataSize maxWorkerMetaspace,
        @DefaultValue("64KB") DataSize maxSourceBytes,
        @DefaultValue("64KB") DataSize maxInputBytes,
        @DefaultValue("64KB") DataSize maxOutputBytes,
        @DefaultValue("4KB") DataSize maxErrorBytes,
        @DefaultValue("4") int poolSize,
        @DefaultValue("100") int queueCapacity) {

    /** 配置错误必须在应用启动时 fail-fast，不能等租户第一次执行时才暴露。 */
    public ScriptSandboxProperties {
        if (maxCpuTime == null || maxCpuTime.isZero() || maxCpuTime.isNegative()
                || maxWallTime == null || maxWallTime.isZero() || maxWallTime.isNegative()
                || maxStartupTime == null || maxStartupTime.isZero() || maxStartupTime.isNegative()
                || maxWorkerHeapMemory == null || maxWorkerHeapMemory.toBytes() < DataSize.ofMegabytes(32).toBytes()
                || maxWorkerMetaspace == null || maxWorkerMetaspace.toBytes() < DataSize.ofMegabytes(32).toBytes()
                || maxSourceBytes == null || maxSourceBytes.toBytes() <= 0
                || maxInputBytes == null || maxInputBytes.toBytes() <= 0
                || maxOutputBytes == null || maxOutputBytes.toBytes() <= 0
                || maxErrorBytes == null || maxErrorBytes.toBytes() < 0
                || poolSize <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("脚本沙箱资源上限配置不合法");
        }
    }
}
