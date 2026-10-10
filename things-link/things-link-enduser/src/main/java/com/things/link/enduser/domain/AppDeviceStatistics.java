package com.things.link.enduser.domain;

import java.time.Instant;

/**
 * 同一数据库快照中的当前账号授权设备统计，不代表整个项目设备。
 * @param total 授权且未删除设备数
 * @param online 当前在线设备数
 * @param active24h 最近24小时有效上报设备数
 * @param alarming 当前未解除告警设备数
 * @param asOf 数据库统计时刻
 */
public record AppDeviceStatistics(long total, long online, long active24h, long alarming, Instant asOf) { }
