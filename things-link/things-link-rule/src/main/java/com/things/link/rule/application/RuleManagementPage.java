package com.things.link.rule.application;
import java.util.List;
/** @param items 有界结果 @param nextCursor 下一页游标，末页为null @param <T> 结果投影 */
public record RuleManagementPage<T>(List<T> items, String nextCursor) {
    /** 结果列表不可变。 */
    public RuleManagementPage { items = List.copyOf(items); }
}
