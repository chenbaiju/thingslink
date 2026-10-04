package com.things.link.project.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次有限期人工运营调整的可追溯元数据（S14-4b）。
 *
 * <p>它回答「这份凭空多出来的额度是谁、为什么、凭哪张工单给的」，因此三项都不可为空：
 * <ul>
 *   <li>{@code reason} —— 补偿/工单/试用延期等明确原因，不得是空白串；</li>
 *   <li>{@code operatorId} —— 做出决定的账号（{@code sys_account.id}）；刻意不设外键，
 *       与 {@code sys_audit_log.actor_account_id} 同口径，账号注销不得让历史调整变成孤儿；</li>
 *   <li>{@code idempotencyKey} —— 调用方幂等键，同一租户内唯一；重放返回既有调整而不写第二行。</li>
 * </ul>
 *
 * <p>本类型只承载「谁/为什么/哪一次」；有效期与额度在包行自身的
 * {@code startsAt/endsAt/amount/dimensionCode} 上，合成规则与购买包完全一致
 * （见 {@link TenantResourcePackage}）。
 *
 * @param reason 调整原因，非空白且不超过 {@value #MAX_REASON_LENGTH} 个字符
 * @param operatorId 操作人账号 ID
 * @param idempotencyKey 幂等键，{@value #MIN_IDEMPOTENCY_KEY_LENGTH}～{@value #MAX_IDEMPOTENCY_KEY_LENGTH} 位
 */
public record ResourcePackageAdjustment(String reason, UUID operatorId, String idempotencyKey) {

    /** 原因列长度上限，与 {@code adjustment_reason varchar(512)} 一致。 */
    public static final int MAX_REASON_LENGTH = 512;

    /** 幂等键最小值：太短的键在运维重放里容易碰撞。 */
    public static final int MIN_IDEMPOTENCY_KEY_LENGTH = 8;

    /** 幂等键最大值，与 {@code adjustment_key varchar(128)} 一致。 */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 幂等键形状，与 {@code sys_tenant_resource_package_adjustment_key_ck} 逐字一致。 */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    /** 元数据必须完整、原因不得为空白、幂等键必须符合冻结形状。 */
    public ResourcePackageAdjustment {
        if (operatorId == null) {
            // 与原因/幂等键同一口径抛 IllegalArgumentException：调用方只需捕获一种结构违规异常，
            // 不会因为「恰好是 null」而得到 500。
            throw new IllegalArgumentException("调整操作人不得为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("调整原因不得为空或空白");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("调整原因不得超过 " + MAX_REASON_LENGTH + " 个字符");
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "调整幂等键必须是 " + MIN_IDEMPOTENCY_KEY_LENGTH + "～" + MAX_IDEMPOTENCY_KEY_LENGTH
                            + " 位字母、数字或 . _ : - 且以字母或数字开头");
        }
    }
}
