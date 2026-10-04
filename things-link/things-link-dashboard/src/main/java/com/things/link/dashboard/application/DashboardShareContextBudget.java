package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.shared.error.BusinessException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * ADR0101分享签发前的context可恢复性预算，避免已发secret对应的有限scope永远超过16KiB。
 * 只测现有变量scope公开投影，固定1KiB留给信封，不依赖HTTP DTO或当前Host注册。
 */
public final class DashboardShareContextBudget {
    /** 合同§4规定最终context正文最多16KiB，运行发送仍按真实编码再次验证。 */
    private static final int CONTEXT_MAXIMUM_BYTES = 16 * 1024;
    /**
     * 为三个规范UUID、正Long、两个UTC时刻、有限三段SemVer及其余固定字段预留1KiB。
     * 该值大于这些字段的最大编码长度；保守准入会拒绝少量理论刚好可放入16KiB的组合。
     */
    private static final int ENVELOPE_RESERVED_BYTES = 1024;
    /** 仅编码已验证ASCII变量键与规范UUID的有限投影，不含秘密或整个Schema。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 无状态纯校验器不需要Spring装配或构造实例。 */
    private DashboardShareContextBudget() { }

    /**
     * 在任何token/恢复映射/审计写入之前验证完整scope恢复包；超限不截断或扩容预算。
     * @param scopes 已由签发资格验证的全部变量及候选集合
     */
    public static void requireFits(List<DashboardShareVariableScope> scopes) {
        if (scopes == null || scopes.size() > 20 || scopes.stream().anyMatch(scope -> scope == null
                || scope.variableKey() == null || scope.variableKey().length() > 64
                || scope.deviceIds().isEmpty() || scope.deviceIds().size() > 20)) {
            throw invalid();
        }
        List<DashboardShareVariableScopeView> projection = scopes.stream().map(scope ->
                new DashboardShareVariableScopeView(scope.variableKey(), scope.deviceIds())).toList();
        if (JSON.writeValueAsBytes(projection).length > CONTEXT_MAXIMUM_BYTES - ENVELOPE_RESERVED_BYTES) {
            throw invalid();
        }
    }

    /** 创建范围无法放入固定context时按60049拒绝，而非签发后永久返回运行503。 */
    private static BusinessException invalid() { return new BusinessException(DashboardErrorCode.SHARE_INVALID); }
}
