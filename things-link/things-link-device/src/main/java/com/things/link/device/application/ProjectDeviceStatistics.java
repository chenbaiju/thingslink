package com.things.link.device.application;

/**
 * 项目设备概要的跨模块只读投影。
 *
 * <p>该类型位于 application 包，telemetry 只能通过本契约取得 device 域统计，禁止直接查询
 * {@code dev_device}。三个计数使用同一条 PostgreSQL 聚合语句，避免概要页观察到不同快照。</p>
 *
 * @param total 未删除设备总数
 * @param online 当前状态为 ONLINE 的设备数
 * @param activeSince 窗口内上线过或当前仍在线的设备数
 */
public record ProjectDeviceStatistics(long total, long online, long activeSince) {
}
