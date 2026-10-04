package com.things.link.ota.domain;

/** 不可变回退报告当时裁决，观察不等于物理动作成功。 */
public record OtaRollbackReportResult(OtaRollbackReport receipt, String disposition, String reason) { }
