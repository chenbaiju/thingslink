package com.things.link.telemetry.domain;

/**
 * 消息日志时间窗统计。
 *
 * @param count 消息条数
 * @param bytes 原始报文字节总数
 */
public record MessageLogStatistics(long count, long bytes) {
}
