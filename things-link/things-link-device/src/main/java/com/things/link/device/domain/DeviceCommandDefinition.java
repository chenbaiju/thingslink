package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备类型命令定义，描述云端向设备发起的 RPC 动作及其输入输出 Schema。
 *
 * <p>与事件定义不同，命令的输入和输出采用 JSON Schema（{@code jsonb}）而非结构化的参数表。
 * 原因是命令的请求/响应可能包含嵌套对象与数组，参数表不擅长表达这种结构；
 * JSON Schema 是描述 RPC 命令签名的行业标准，且可直接在 S3 命令下发与 S8 规则引擎中共用。</p>
 *
 * @param id 命令 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param deviceTypeId 设备类型 ID
 * @param commandKey 稳定标识符
 * @param name 中文展示名称
 * @param description 命令用途说明
 * @param inputSchema 输入 JSON Schema（可空）
 * @param outputSchema 输出 JSON Schema（可空）
 * @param timeoutSeconds 命令执行超时秒数
 * @param sortOrder 显示顺序
 * @param createdAt 创建时间
 */
public record DeviceCommandDefinition(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                      String commandKey, String name, String description,
                                      String inputSchema, String outputSchema,
                                      int timeoutSeconds, int sortOrder, Instant createdAt) {
}
