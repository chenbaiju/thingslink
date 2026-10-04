package com.things.link.ota.api;

import com.things.link.ota.domain.OtaTrustState;
import java.time.Instant;

/**
 * 信任登记公开摘要，不宣称当前发布资格或设备确认，不输出签名和内部正文。
 * @param trustDomain 信任域
 * @param revision 当前修订十进制字符串
 * @param bundleVersion 包版本十进制字符串
 * @param policyRevision 配置修订十进制字符串
 * @param rootProfile 根签名配置
 * @param rootFingerprint 根指纹
 * @param bundleSha256 包摘要
 * @param createdAt 首次登记时间
 * @param updatedAt 当前更新时刻
 */
public record OtaTrustResponse(String trustDomain, String revision, String bundleVersion, String policyRevision,
        String rootProfile, String rootFingerprint, String bundleSha256, Instant createdAt, Instant updatedAt) {
    /** 只投影登记事实，不序列化内部实体。 */
    public static OtaTrustResponse from(OtaTrustState state) {
        return new OtaTrustResponse(state.trustDomain(), Long.toString(state.revision()),
                Long.toString(state.bundleVersion()), Long.toString(state.policyRevision()), state.rootProfile(),
                state.rootFingerprint(), state.bundleSha256(), state.createdAt(), state.updatedAt());
    }
}
