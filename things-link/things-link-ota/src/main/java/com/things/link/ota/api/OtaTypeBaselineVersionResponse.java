package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.domain.OtaTypeBaselineRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 已登记类型基线的版本历史条目，只回答「哪一版、规范摘要是什么、何时登记」。
 *
 * <h2>为什么不复用 {@link OtaTypeBaselineResponse}</h2>
 * 既有响应内嵌完整的 {@link OtaTypeBaselineBody}：{@code rootFingerprint}、{@code productKey}、
 * {@code trustDomain}、{@code signatureProfiles}、RAM/Flash 上限、AB 槽、Range/断点续传、
 * {@code protectedSecurityCounterBits}、压缩与差分方式、{@code propertyProfile} 与
 * {@code evidenceReference}。这些都是登记入口<b>只从启动配置接受</b>的运营能力配置
 * （{@code things-link.ota.type-baselines-json}），HTTP 正文永远无法注入，
 * 目前也只对 OWNER/ADMIN 的管理读取可见。
 *
 * <p>历史集合走 {@code ota:read}，即<b>任意项目成员</b>（含 VIEWER）可读。若复用它，
 * 等于把受控能力配置与离线根指纹下放给每个成员——因此这里新建最小历史 DTO，
 * 而不是让一个成员可读的入口继承管理读取的字段面。
 *
 * <h2>字段白名单（逐项理由）</h2>
 * <ul>
 *   <li>{@code baselineVersion}：历史的身份与排序键，控制台据此核对版本演进；</li>
 *   <li>{@code baselineHash}：该版本规范字节的 SHA256，控制台据此比对「服务端登记的规范内容」
 *       与外部发布链路冻结的摘要是否一致，也是唯一能替代正文的完整性凭证；</li>
 *   <li>{@code registeredAt}：该版本登记时刻（{@code created_at}），用于时间线展示。</li>
 * </ul>
 *
 * <p>刻意省略：规范字节 {@code canonical} 与由其派生的全部能力/制造字段、{@code tenantId}/
 * {@code projectId}/{@code deviceTypeId}（父范围已由请求路径给出）、{@code revision}
 * （头修订，历史行不持久化；版本号本身已单调标识第几次登记）。
 * 目前也没有「已在别处公开的能力摘要」可复用：设备侧能力摘要属于报告与资格端点，
 * 与管理面类型基线不是同一事实，不能张冠李戴，因此本片不引入任何能力字段。
 *
 * @param baselineVersion 受控基线版本，同类型内严格单调
 * @param baselineHash 规范字节SHA256
 * @param registeredAt 该版本登记时刻
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaTypeBaselineVersionResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaTypeBaselineVersionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
        long baselineVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String baselineHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant registeredAt) {
    /** 只由已校验持久投影生成typed响应，不做任何正文解码。 */
    public static OtaTypeBaselineVersionResponse from(OtaTypeBaselineRepository.Version value) {
        return new OtaTypeBaselineVersionResponse(value.baselineVersion(), value.baselineHash(), value.createdAt());
    }
}
