package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.application.OtaTrustBundleCodec;
import com.things.link.ota.domain.OtaTrustState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * S13-4e-8（D-152③）冻结的信任域读取合同：成员可读的信任域摘要，含当前ACTIVE发布键摘要。
 *
 * <h2>为什么新建DTO而不是复用 {@link OtaTrustResponse}</h2>
 * 单域读取响应只有<b>离线根</b>字段（{@code rootProfile}/{@code rootFingerprint}），没有当前
 * <b>发布键</b>的版本与指纹——而这两项正是控制台渲染信任/密钥表的最小事实。
 * 若强行复用它，要么丢掉合同要求的active-key摘要，要么改写既有
 * {@code GET .../trust-domains/{trustDomain}} 的字段面。这里改为新增最小摘要DTO，
 * 域级字段名与既有响应<b>逐字一致</b>（revision/bundleVersion/policyRevision/rootProfile/
 * rootFingerprint/bundleSha256/createdAt/updatedAt），控制台可共用字段语义。
 *
 * <h2>冻结访问合同</h2>
 * <p><b>可读</b>：域名、修订、包版本、受控配置修订、根Profile与根指纹、包摘要、当前ACTIVE发布键
 * 版本与指纹、域创建/更新时刻。
 *
 * <p><b>永不暴露</b>：私钥材料、任何secret/token、规范包字节{@code canonicalBundle}、根签名字节
 * {@code signature}、受控配置摘要{@code policyHash}、{@code tenantId}/{@code projectId}
 * （父范围已由请求路径给出），以及一切可以<b>改变</b>密钥状态的东西——本片不新增任何
 * rotate/retire/import状态变更端点，状态变更留在独立的安全切片。
 *
 * <h2>字段白名单（逐项理由）</h2>
 * <ul>
 *   <li>{@code trustDomain}：表主键与展示身份；</li>
 *   <li>{@code revision}/{@code bundleVersion}/{@code policyRevision}：十进制字符串，
 *       与既有单域读取完全一致，避免前端整数精度损失；</li>
 *   <li>{@code rootProfile}/{@code rootFingerprint}：离线根身份摘要，既有成员可读单域读取
 *       已公开；</li>
 *   <li>{@code bundleSha256}：当前包规范字节摘要，用于核对发布链路冻结的摘要；</li>
 *   <li>{@code activeKeyVersion}/{@code activeKeyFingerprint}：当前包内state=ACTIVE的发布键
 *       版本与SPKI摘要，是密钥表的最小定位信息（不含公钥字节）；</li>
 *   <li>{@code createdAt}/{@code updatedAt}：域首次登记与最近导入时刻。</li>
 * </ul>
 *
 * @param trustDomain 信任域，全局唯一主键
 * @param revision 当前修订十进制字符串
 * @param bundleVersion 当前包版本十进制字符串
 * @param policyRevision 受控根配置修订十进制字符串
 * @param rootProfile 离线根签名Profile
 * @param rootFingerprint 离线根完整SHA256
 * @param bundleSha256 当前包规范字节SHA256
 * @param activeKeyVersion 当前ACTIVE发布键版本
 * @param activeKeyFingerprint 当前ACTIVE发布键完整SHA256
 * @param createdAt 域名首次登记时刻
 * @param updatedAt 最近导入时刻
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaTrustDomainSummaryResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaTrustDomainSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        String trustDomain,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String bundleVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String policyRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"}) String rootProfile,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String rootFingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String bundleSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9._:/-]{1,256}") String activeKeyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String activeKeyFingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant updatedAt) {
    /** 只从已登记域事实与当前包公开摘要投影，不序列化规范字节、签名或受控配置摘要。 */
    public static OtaTrustDomainSummaryResponse from(OtaTrustState state,
            OtaTrustBundleCodec.PublicKeyMetadata activeKey) {
        return new OtaTrustDomainSummaryResponse(state.trustDomain(), Long.toString(state.revision()),
                Long.toString(state.bundleVersion()), Long.toString(state.policyRevision()), state.rootProfile(),
                state.rootFingerprint(), state.bundleSha256(), activeKey.keyVersion(), activeKey.fingerprint(),
                state.createdAt(), state.updatedAt());
    }
}
