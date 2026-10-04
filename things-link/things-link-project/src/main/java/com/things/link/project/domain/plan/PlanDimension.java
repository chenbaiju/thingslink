package com.things.link.project.domain.plan;

import java.util.Objects;

/**
 * 产品修订版的一个冻结数值维度（编码 + 数值 + 单位 + 窗口）。
 *
 * <p>数值不可为 {@code null}：S14-0 冻结明确禁止用 {@code NULL} 表达「不限」，
 * 未知项必须以确定值或权益 {@code DISABLED} 表达。单位与窗口区分「日额度」「分钟速率」
 * 「并发」「滚动历史窗口」与「存量」，运行时不得自行猜测。
 *
 * @param code 冻结维度编码，如 {@code PROJECTS_MAX}、{@code HISTORY_WINDOW}
 * @param value 冻结数值；只有 {@code EXTERNAL_COLLABORATOR_SEATS} 允许为 0
 * @param unit 数值单位，如 {@code COUNT}、{@code MESSAGE}、{@code MONTH}、{@code GB}
 * @param window 计量窗口，如 {@code NONE}、{@code UTC_DAY}、{@code MINUTE}、{@code CONCURRENT}、{@code ROLLING}
 */
public record PlanDimension(String code, long value, String unit, String window) {

    /** 冻结维度必须是格式合法的确定值，避免未知项以 null/0 混入。 */
    public PlanDimension {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("套餐维度编码不合法");
        }
        if (value < 0) {
            throw new IllegalArgumentException("套餐维度数值不得为负数");
        }
        Objects.requireNonNull(unit, "维度单位不得为空");
        Objects.requireNonNull(window, "维度窗口不得为空");
    }
}
