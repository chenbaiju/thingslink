package com.things.link.ota.domain;

import com.things.link.shared.error.ErrorCode;

/** 安装前停止必须具备独立受控耐久互斥能力。 */
public enum OtaInstallStopErrorCode implements ErrorCode {
    /** 缺配置或原基线不匹配，不能假设设备可停止安装。 */
    UNAVAILABLE;
    /** 已登记公开业务编号。 */
    @Override public int code() { return 70045; }
    /** 固定消息不含受控配置。 */
    @Override public String defaultMessage() { return "OTA安装前停止能力未配置或不可用"; }
    /** 能力不可用不能伪装已取消。 */
    @Override public int httpStatus() { return 503; }
}
