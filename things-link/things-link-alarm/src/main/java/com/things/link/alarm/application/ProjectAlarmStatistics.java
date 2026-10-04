package com.things.link.alarm.application;

/** 有效设备按最高ACTIVE严重度互斥归类，非告警设备由概要总数相减得到。 */
public record ProjectAlarmStatistics(long critical, long major, long minor, long warning, long info) {
    public ProjectAlarmStatistics {
        if (critical < 0 || major < 0 || minor < 0 || warning < 0 || info < 0) {
            throw new IllegalArgumentException("告警设备数不能为负");
        }
    }
    public long activeDevices() { return critical + major + minor + warning + info; }
}
