package com.things.link.dashboard.application.schema;

/**
 * 看板Schema原文解析端口。
 *
 * <p>S12-0b1第2.1节要求在任何DTO、Map或数据库转换之前检查原文字节、重复键和数字词法，
 * 因此调用方必须提交收到的UTF-8字节，不能先转为字符串或JSON树。</p>
 */
public interface DashboardSchemaParser {
    /**
     * 解析并识别看板Schema合同版本。
     *
     * @param source 收到的原始UTF-8字节
     * @return 已通过语法及资源守卫的Schema
     * @throws DashboardSchemaParseException 原文、JSON结构或合同版本不合法
     */
    ParsedDashboardSchema parse(byte[] source);
}
