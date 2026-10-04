package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.DashboardSchemaContractVersion;
import tools.jackson.databind.JsonNode;

/**
 * 已通过原文守卫并识别版本的看板Schema。
 *
 * <p>实现必须在接收和返回JSON树时都复制，防止调用方修改共享解析事实并绕过后续业务校验。</p>
 */
public interface ParsedDashboardSchema {
    /**
     * 返回已识别的合同版本。
     *
     * @return 看板Schema合同版本
     */
    DashboardSchemaContractVersion contractVersion();

    /**
     * 返回隔离的JSON根对象副本。
     *
     * @return 后续业务校验可安全读取或修改的副本
     */
    JsonNode root();
}
