package com.things.link.ota.domain;

/** 不可变停止报告当时裁决，观察不等于物理动作成功。 */
public record OtaInstallStopReportResult(OtaInstallStopReport receipt, String disposition, String reason) { }
