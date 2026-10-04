package com.things.link.telemetry.application;

import java.time.Instant;

/**
 * 项目概要的稳定应用投影，同时也是 Redis 派生值协议。
 *
 * @param generatedAt 快照生成时刻
 * @param window 24 小时统计窗口
 * @param devices 设备概要
 * @param messages24h 24 小时消息概要
 * @param alarmRate ACTIVE 告警设备率；S6 起由 PostgreSQL 告警实例事实生成
 */
public record OverviewSnapshot(Instant generatedAt, Window window, DeviceSummary devices,
                               MessageSummary messages24h, AvailabilityRate alarmRate,
                               AlarmSeverityDeviceCounts alarmSeverityDeviceCounts) {
    public OverviewSnapshot {
        if (devices == null || alarmSeverityDeviceCounts == null
                || alarmSeverityDeviceCounts.total() != devices.total()) {
            throw new IllegalArgumentException("告警分类必须覆盖全部有效设备");
        }
        double expectedRate = devices.total() == 0 ? 0D
                : (double) (devices.total() - alarmSeverityDeviceCounts.normal()) / devices.total();
        if (alarmRate == null || !alarmRate.available() || alarmRate.value() == null
                || Double.compare(alarmRate.value(), expectedRate) != 0) {
            throw new IllegalArgumentException("告警率与分类快照不一致");
        }
    }

    public record AlarmSeverityDeviceCounts(long normal, long critical, long major,
                                           long minor, long warning, long info) {
        public AlarmSeverityDeviceCounts {
            if (normal < 0 || critical < 0 || major < 0 || minor < 0 || warning < 0 || info < 0) {
                throw new IllegalArgumentException("设备分类数量不能为负");
            }
        }
        public long total() { return normal + critical + major + minor + warning + info; }
    }

    /** @param from 窗口起点（包含） @param to 窗口终点（不包含） */
    public record Window(Instant from, Instant to) {
    }

    /**
     * @param total 设备总数
     * @param online 当前在线数
     * @param onlineRate 在线率，项目无设备时为 0
     * @param active24h 24 小时活跃数
     * @param active24hRate 24 小时活跃率，项目无设备时为 0
     */
    public record DeviceSummary(long total, long online, double onlineRate,
                                long active24h, double active24hRate) {
    }

    /** @param count 消息数 @param bytes 原始报文字节数 */
    public record MessageSummary(long count, long bytes) {
    }

    /**
     * @param available 指标是否已有事实源；S6 起固定为 true
     * @param value ACTIVE 告警影响设备数除以项目设备总数，范围为 [0,1]
     */
    public record AvailabilityRate(boolean available, Double value) {
    }
}
