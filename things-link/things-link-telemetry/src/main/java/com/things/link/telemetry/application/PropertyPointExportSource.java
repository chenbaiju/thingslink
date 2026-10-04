package com.things.link.telemetry.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** ADR0075 时序属性原始点的流式导出端口。 */
public interface PropertyPointExportSource {

    /**
     * 在调用方单一快照内流出项目全部原始属性点，不读取连续聚合或消息原文。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目 ID
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamPropertyPoints(UUID tenantId, UUID projectId, PropertyPointSink sink);

    /**
     * {@code property-points.jsonl} 的逐字段白名单事实。
     *
     * @param deviceId 设备 ID
     * @param propertyKey 属性标识
     * @param ts 设备采集时刻
     * @param messageId 原消息身份，不含消息正文
     * @param dataType 数据类型
     * @param thingModelVersionId 物模型版本 ID
     * @param modelVersion 语义版本
     * @param valueDouble 数值
     * @param valueText 文本或枚举
     * @param valueBool 布尔值
     * @param valueJson 复杂值
     * @param quality 质量码
     */
    record PropertyPointExportRow(UUID deviceId, String propertyKey, Instant ts, UUID messageId,
                                  String dataType, UUID thingModelVersionId, String modelVersion,
                                  Double valueDouble, String valueText, Boolean valueBool,
                                  JsonNode valueJson, short quality) {
    }

    /** 单行属性点回调；实现必须同步消费，禁止持有整个结果集。 */
    @FunctionalInterface
    interface PropertyPointSink {
        /** @param point 当前原始属性点 */
        void accept(PropertyPointExportRow point);
    }
}
