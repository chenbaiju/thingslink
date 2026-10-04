package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.application.OtaTrustBundleCodec;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * S13-4e-8（D-152③）冻结的发布键读取合同：单个发布键的<b>公开元数据</b>白名单。
 *
 * <h2>冻结访问合同</h2>
 * <p><b>可读</b>：keyVersion、state、fingerprint、algorithm/profile、有效期窗口
 * （{@code notBefore}/{@code notAfter}，UTC epoch秒，与{@code tc-ota-trust-bundle/v1}冻结语义一致）。
 *
 * <p><b>永不暴露</b>：私钥材料、任何secret/token、公钥字节{@code spki}、规范包字节、签名字节、
 * {@code tenantId}/{@code projectId}，以及一切能让读者<b>改变</b>密钥状态的东西。本DTO没有也不会有
 * 任何可写字段；本片不新增rotate/retire/import状态变更端点，密钥状态变更留在独立安全切片。
 *
 * <h2>字段白名单（逐项理由）</h2>
 * <ul>
 *   <li>{@code keyVersion}：不可变键身份与分页排序键；</li>
 *   <li>{@code state}：四态生命周期{@code PREPARED|ACTIVE|VERIFY_ONLY|REVOKED}，
 *       是只读事实，不含变更操作；</li>
 *   <li>{@code signatureProfile}：固定签名Profile（"algorithm/profile"），
 *       告诉运维该键属于哪套受控算法族；</li>
 *   <li>{@code fingerprint}：完整SPKI SHA256，键的稳定标识；</li>
 *   <li>{@code notBefore}/{@code notAfter}：有效期窗口，UTC epoch秒，左闭右开。</li>
 * </ul>
 *
 * <p>刻意省略{@code spki}：指纹已足够标识键，成员可读密钥表不需要公钥字节，少一个字段少一条
 * 误用分发面。刻意省略逐键创建/退役时刻：冻结包合同没有这两个字段，从包登记时刻反推单个键的
 * 生命周期只会编造事实；域级登记时刻由{@link OtaTrustDomainSummaryResponse#createdAt()}给出。
 *
 * @param keyVersion 不可变版本标识
 * @param state 四态生命周期
 * @param signatureProfile 固定签名Profile
 * @param fingerprint 完整SPKI摘要
 * @param notBefore 包含的有效起点epoch秒
 * @param notAfter 不包含的有效终点epoch秒
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaTrustKeyResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaTrustKeyResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9._:/-]{1,256}") String keyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PREPARED", "ACTIVE", "VERIFY_ONLY", "REVOKED"}) String state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"}) String signatureProfile,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String fingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "253402300799",
                description = "UTC epoch秒，左闭") long notBefore,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "253402300799",
                description = "UTC epoch秒，右开且严格大于notBefore") long notAfter) {
    /** 只投影公开元数据；SPKI与规范字节不在来源记录中，因此不可能被序列化出去。 */
    public static OtaTrustKeyResponse from(OtaTrustBundleCodec.PublicKeyMetadata value) {
        return new OtaTrustKeyResponse(value.keyVersion(), value.state(), value.profile().value(),
                value.fingerprint(), value.notBefore(), value.notAfter());
    }
}
