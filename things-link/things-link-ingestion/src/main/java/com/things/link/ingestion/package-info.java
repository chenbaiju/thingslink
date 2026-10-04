/**
 * 设备接入、原始消息生产与标准消息消费模块。
 *
 * <p>S3-11B 只建立模块和 Kafka 工程基础设施。业务链路按后续纵向切片接入，避免
 * 在没有真实 Kafka 分区与 traceId 测试保护时一次堆入 producer、consumer 和遥测写入。</p>
 */
package com.things.link.ingestion;
