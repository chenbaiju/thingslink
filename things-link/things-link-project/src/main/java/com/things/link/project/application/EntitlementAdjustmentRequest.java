package com.things.link.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次运营人工调整的提交请求（S14-4b）。
 *
 * <p>这是**未校验的输入载体**：字段合法性（维度是否可扩容、额度是否为正、有效期是否合法、
 * 原因/操作人/幂等键是否齐全）全部由 {@link TenantEntitlementAdjustmentService} 判定并以
 * 业务错误码返回，因此本记录不做结构校验，以免把「缺操作人」变成 500 而掩盖成编程错误。
 *
 * <p>字段与冻结口径一一对应：
 * <ul>
 *   <li>{@code dimensionCode} —— 取自 {@code product-revision-1} 的冻结维度，必须是可扩容维度；</li>
 *   <li>{@code amount} —— 该维度要增加的额度（恒为正，提高上限而不是充值余额）；</li>
 *   <li>{@code startsAt}/{@code endsAt} —— 调整自身的服务期窗口，不能省略终点（禁止无期限调整）；</li>
 *   <li>{@code reason}/{@code operatorId}/{@code idempotencyKey} —— 原因、操作人与幂等键。</li>
 * </ul>
 *
 * @param dimensionCode 目标冻结维度编码
 * @param amount 该维度增加的额度
 * @param startsAt 调整生效起点（UTC）
 * @param endsAt 调整生效终点（UTC），不得为空
 * @param reason 调整原因
 * @param operatorId 操作人账号 ID
 * @param idempotencyKey 幂等键，同一租户内唯一
 */
public record EntitlementAdjustmentRequest(
        String dimensionCode,
        long amount,
        Instant startsAt,
        Instant endsAt,
        String reason,
        UUID operatorId,
        String idempotencyKey) {
}
