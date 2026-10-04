package com.things.link.ingestion.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * EMQX 上行回调处理结果。
 *
 * @param accepted 是否已将合法且未超限的设备消息交给 Kafka；false 仍通过 HTTP 200 确认回调
 */
@Schema(name = "EmqxMessagePublishedResponse", description = "EMQX 上行回调处理结果")
public record EmqxMessagePublishedResponse(boolean accepted) {
}
