package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 已登记类型基线，读取不等于当前部署配置或设备资格。
 * @param tenantId 真实租户
 * @param projectId 真实项目
 * @param deviceTypeId 精确设备类型
 * @param revision 头单调修订
 * @param baselineVersion 受控基线版本
 * @param baselineHash 规范字节SHA256
 * @param canonical 完整规范基线字节
 * @param createdAt 首次登记时间
 * @param updatedAt 最近登记时间
 */
public record OtaTypeBaselineState(UUID tenantId,UUID projectId,UUID deviceTypeId,long revision,long baselineVersion,
                                   String baselineHash,byte[] canonical,Instant createdAt,Instant updatedAt) {
    /** 不共享外部可变数组。 */
    public OtaTypeBaselineState { canonical=canonical.clone(); }
    /** 不暴露内部可变数组。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
