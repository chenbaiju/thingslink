package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 看板Schema语义管线的规范化结果。
 *
 * <p>scope只证明Schema内部语义；外部需求、持久化摘要与发布事务未完成时不能发布或渲染。</p>
 */
final class DashboardSchemaValidationResult {
    /** 当前已经机器证明的校验范围。 */
    private final Scope scope;
    /** 注入当前范围默认值后的隔离JSON树。 */
    private final JsonNode normalizedRoot;
    /** 完整内部语义校验后仍须由外部端口证明的事实需求。 */
    private final List<DashboardSchemaExternalRequirement> unresolvedRequirements;

    /**
     * 创建带显式完成范围的规范化结果。
     *
     * @param scope 已完成校验范围
     * @param normalizedRoot 规范化JSON根
     * @param unresolvedRequirements 完整内部语义派生的外部事实需求
     */
    DashboardSchemaValidationResult(
            Scope scope, JsonNode normalizedRoot,
            List<DashboardSchemaExternalRequirement> unresolvedRequirements) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.normalizedRoot = Objects.requireNonNull(normalizedRoot, "normalizedRoot").deepCopy();
        this.unresolvedRequirements = List.copyOf(unresolvedRequirements);
    }

    /**
     * 返回当前机器证明的范围。
     *
     * @return 校验范围
     */
    Scope scope() {
        return scope;
    }

    /**
     * 返回隔离的规范化JSON树。
     *
     * @return JSON根副本
     */
    JsonNode normalizedRoot() {
        return normalizedRoot.deepCopy();
    }

    /**
     * 返回完整内部语义校验后仍待外部端口证明的事实需求。
     *
     * <p>列表为空只表示当前Schema没有外部引用，不能据此推导持久化、授权、宿主或发布资格成立。</p>
     *
     * @return 不可变的当前范围需求列表
     */
    List<DashboardSchemaExternalRequirement> unresolvedRequirements() {
        return unresolvedRequirements;
    }

    /**
     * 返回规范化树的紧凑UTF-8表示；该字节只用于本层资源计数，不是PG权威摘要输入。
     *
     * @return 紧凑JSON字节
     */
    byte[] normalizedUtf8() {
        return normalizedRoot.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 校验管线能够显式报告的完成范围。 */
    enum Scope {
        /** 已完成当前合同全部内部结构、默认、引用、布局及十组件组合语义；外部需求仍须独立证明。 */
        COMPLETE_INTERNAL_SCHEMA_SEMANTICS
    }
}
