package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 固件软生命周期及不可变操作记录，不包含对象地址。
 * @param firmware 原固件完整事实
 * @param deprecation 退役记录，未发生为空
 * @param revocation 撤销记录，未发生为空
 */
public record OtaFirmwareLifecycleState(OtaFirmware firmware,Transition deprecation,Transition revocation) {
    /** 一次不可改写的生命周期裁决。
     * @param reason 明确原因
     * @param actorId 真实操作者
     * @param occurredAt 服务端微秒时间
     */
    public record Transition(String reason,UUID actorId,Instant occurredAt) { }
}
