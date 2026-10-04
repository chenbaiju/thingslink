package com.things.link.device.domain;

/**
 * 设备域内部的统计结果。
 *
 * @param total 未删除设备总数
 * @param online 当前在线设备数
 * @param activeSince 窗口内活跃设备数
 */
public record DeviceStatistics(long total, long online, long activeSince) {
}
