package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 设备最近一次经认证报告，不表示当前仍具备升级资格。
 * @param tenantId 认证租户
 * @param projectId 认证项目
 * @param deviceId 认证设备
 * @param credentialVersion 原始认证代际
 * @param reportSequence 同代际单调序号
 * @param revision 持久事实修订
 * @param committedSecurityVersion 跨代际不可降低的观察下限
 * @param canonical 规范报告字节
 * @param reportHash 规范报告SHA256
 * @param brokerReceivedAt 原始Broker接收时间
 * @param acceptedAt 本事实首次平台接纳时间
 */
public record OtaDeviceReportState(UUID tenantId, UUID projectId, UUID deviceId,
                                  long credentialVersion, long reportSequence, long revision,
                                  long committedSecurityVersion, byte[] canonical, String reportHash,
                                  Instant brokerReceivedAt, Instant acceptedAt) {
    /** 独占规范字节。 */
    public OtaDeviceReportState { canonical = canonical.clone(); }
    /** 防止调用者修改已接纳证据。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
