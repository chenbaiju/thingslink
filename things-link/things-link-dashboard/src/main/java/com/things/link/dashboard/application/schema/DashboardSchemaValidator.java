package com.things.link.dashboard.application.schema;

/**
 * 看板Schema纯结构校验与规范化端口。
 *
 * <p>入口接收原始字节并先调用装配的原文解析端口；生产装配必须提供严格实现，不能把测试替身用于保存或发布。</p>
 */
interface DashboardSchemaValidator {
    /**
     * 创建固定按原文解析、再按语义规则处理的门面。
     *
     * @param parser 严格原文解析端口
     * @return 不暴露内部语义实现的校验门面
     */
    static DashboardSchemaValidator using(DashboardSchemaParser parser) {
        return new DefaultDashboardSchemaValidator(parser);
    }

    /**
     * 按当前已登记范围校验结构、注入唯一默认值并显式报告完成范围。
     *
     * @param source 看板Schema原始UTF-8字节
     * @return 带明确完成范围的规范化结果
     * @throws DashboardSchemaParseException 原文解析失败
     * @throws DashboardSchemaValidationException 当前结构、值或规范化规则失败
     */
    DashboardSchemaValidationResult validateAndNormalize(byte[] source);
}
