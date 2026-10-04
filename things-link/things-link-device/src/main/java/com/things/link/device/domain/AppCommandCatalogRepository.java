package com.things.link.device.domain;

import java.util.List;
import java.util.UUID;

/** ADR0110独立有界目录仓储，不改变Console既有定义列表。 */
public interface AppCommandCatalogRepository {
    /** 至多读取101项；SQL在大字段进入Java前检测64KiB限制。
     * @param projectId 项目 @param deviceTypeId 真实设备类型 @return 安全候选
     */
    List<Entry> findBounded(UUID projectId, UUID deviceTypeId);

    /** @param commandKey 命令键 @param name 名称 @param description 说明
     * @param inputSchema 输入Schema @param outputSchema 输出Schema @param timeoutSeconds 超时
     * @param oversized SQL检测到过大字段，调用方须整体拒绝而非将null当合法Schema
     */
    record Entry(String commandKey, String name, String description, String inputSchema,
                 String outputSchema, int timeoutSeconds, boolean oversized) { }
}
