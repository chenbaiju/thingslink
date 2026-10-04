package com.things.link.entitlement;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** 纯 UTC 期限计算；可信时钟和持久状态由其他 SHC 切片处理。 */
public final class GrantTermV1 {
    private static final Duration GRACE = Duration.ofHours(15L * 24L);

    public enum State { ACTIVE, GRACE, COLLECT_ONLY }

    private GrantTermV1() { }

    /** 按 UTC 公历计算周年并保留纳秒；闰年 2 月 29 日在下一非闰年落到 2 月 28 日。 */
    public static Instant firstPaidEnd(Instant startsAt) {
        return Objects.requireNonNull(startsAt, "startsAt").atOffset(ZoneOffset.UTC)
                .plusYears(1).toInstant();
    }

    /** 从排他的付费期限终点起，计算十五个完整的 24 小时周期。 */
    public static Instant graceEnd(Instant endsAt) {
        return Objects.requireNonNull(endsAt, "endsAt").plus(GRACE);
    }

    /** 调用方必须提供已验证授权和可信的当前 UTC 时间。 */
    public static State state(String tier, Instant endsAt, Instant now) {
        Objects.requireNonNull(now, "now");
        if ("FREE".equals(tier)) {
            if (endsAt != null) throw new IllegalArgumentException("FREE end is forbidden");
            return State.ACTIVE;
        }
        if (!"STANDARD".equals(tier) && !"ENTERPRISE".equals(tier)
                && !"PROFESSIONAL".equals(tier)) {
            throw new IllegalArgumentException("unknown paid tier");
        }
        Instant end = Objects.requireNonNull(endsAt, "paid end");
        if (now.isBefore(end)) return State.ACTIVE;
        if (now.isBefore(graceEnd(end))) return State.GRACE;
        return State.COLLECT_ONLY;
    }
}
