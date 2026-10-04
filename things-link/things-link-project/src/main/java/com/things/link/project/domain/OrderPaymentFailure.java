package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一行支付失败事实（S14-5b）：一次支付尝试的失败结果。
 *
 * <p>它只回答「哪一笔订单、哪个渠道、哪一条渠道事件、因为什么失败、失败时金额多少」。
 * 它<b>不携带任何权益或状态后果</b>：失败不改变订单状态（订单保持 {@code CREATED} 可重试）、
 * 不创建订阅或资源包、不推进配额绑定版本。这条边界是本片的核心，由真库用例逐项钉住。
 *
 * <p>{@code failureCode} 是渠道自己的失败分类（大写枚举风格），本类型只校验形状与长度：
 * 各家渠道的取值不同，写成闭集只会逼着每接一家渠道改一次代码。
 *
 * @param id 失败行 ID
 * @param tenantId 失败归属租户
 * @param orderId 失败订单 ID
 * @param provider 支付渠道
 * @param providerEventId 渠道失败事件 ID（幂等键）
 * @param failureCode 渠道失败分类
 * @param amountCents 失败时刻订单金额快照（人民币分）
 * @param currency ISO 4217 币种快照
 * @param occurredAt 渠道报告的发生时刻（UTC）
 * @param createdAt 失败事实写入时刻（UTC）
 */
public record OrderPaymentFailure(
        UUID id,
        UUID tenantId,
        UUID orderId,
        PaymentProvider provider,
        String providerEventId,
        String failureCode,
        long amountCents,
        String currency,
        Instant occurredAt,
        Instant createdAt) {

    /** 失败分类形状，与 {@code sys_tenant_order_payment_failure_code_ck} 逐字一致。 */
    private static final Pattern FAILURE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    /** 失败事实必须完整且自洽：失败分类合法、金额非负、时刻齐备。 */
    public OrderPaymentFailure {
        Objects.requireNonNull(id, "失败行 ID 不得为空");
        Objects.requireNonNull(tenantId, "失败租户 ID 不得为空");
        Objects.requireNonNull(orderId, "失败订单 ID 不得为空");
        Objects.requireNonNull(provider, "失败渠道不得为空");
        Objects.requireNonNull(currency, "失败币种不得为空");
        Objects.requireNonNull(occurredAt, "失败发生时刻不得为空");
        Objects.requireNonNull(createdAt, "失败写入时刻不得为空");
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("渠道失败事件 ID 不得为空或空白");
        }
        if (failureCode == null || !FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("失败分类必须是大写枚举风格且长度 3～64");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("失败快照金额不得为负数");
        }
    }
}
