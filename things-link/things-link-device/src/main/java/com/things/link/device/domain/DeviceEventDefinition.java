package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 设备类型事件定义及其参数 Schema。
 * @param id 事件 ID @param tenantId 租户 ID @param projectId 项目 ID @param deviceTypeId 类型 ID
 * @param eventKey 消息 method @param name 名称 @param level 级别 @param description 说明
 * @param sortOrder 排序 @param parameters 参数定义 @param createdAt 创建时间
 */
public record DeviceEventDefinition(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                    String eventKey, String name, Level level, String description, int sortOrder,
                                    List<Parameter> parameters, Instant createdAt) {
    /** 事件重要程度；ERROR 用于需要立即处理的故障事件。 */
    public enum Level { /** 普通信息。 */ INFO, /** 风险警告。 */ WARNING, /** 设备故障。 */ ERROR }

    /**
     * 事件 params 对象中的单个参数定义。
     * @param id 参数 ID @param parameterKey 参数键 @param name 名称 @param dataType 数据类型
     * @param required 是否必填 @param enumOptions 枚举值 @param sortOrder 排序
     */
    public record Parameter(UUID id, String parameterKey, String name, DevicePropertyDefinition.DataType dataType,
                            boolean required, List<String> enumOptions, int sortOrder) { }

    /** 应用服务接收的参数草稿，不携带由平台生成的 ID。 */
    public record ParameterDraft(String parameterKey, String name, DevicePropertyDefinition.DataType dataType,
                                 boolean required, List<String> enumOptions, int sortOrder) { }
}
