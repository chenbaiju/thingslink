package com.things.link.project.domain.plan;

import java.util.Objects;

/**
 * 一个套餐在某个修订版上的最小身份投影：稳定编码 + 展示名 + 修订版标识/序号。
 *
 * <p>它只回答「是哪一档、哪一版」，不携带价格、权益或额度：价格与额度必须从冻结快照读，
 * 不能由本记录二次拼接。S14-2c 的租户套餐摘要用它分别表达「订阅锁定的档位」与
 * 「运行时指针实际绑定的档位」，两者不得互相冒充。
 *
 * @param code 稳定套餐编码，如 {@code FREE}
 * @param name 展示名称（中文）
 * @param revision 产品修订版标识，如 {@code product-revision-1}
 * @param revisionNo 同一套餐的单调修订序号
 */
public record PlanIdentity(String code, String name, String revision, int revisionNo) {

    /** 身份字段必须完整且确定，禁止用空值或 0 冒充未知档位。 */
    public PlanIdentity {
        Objects.requireNonNull(code, "套餐编码不得为空");
        Objects.requireNonNull(name, "套餐名称不得为空");
        Objects.requireNonNull(revision, "产品修订版标识不得为空");
        if (revisionNo <= 0) {
            throw new IllegalArgumentException("修订序号必须为正数");
        }
    }
}
