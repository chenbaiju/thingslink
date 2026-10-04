package com.things.link.ingestion.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.ingestion.api.dto.response.EmqxMessagePublishedResponse;
import com.things.link.ingestion.application.RawUplinkIngestionService;
import com.things.link.support.tenant.DataPlaneDatabase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** EMQX 规则引擎上行入口，只负责 HTTP 绑定并调用 ingestion 用例。 */
@Tag(name = "EMQX 上行消息", description = "接收 EMQX message.publish 规则回调并写入 Kafka 原始主题")
@DataPlaneDatabase
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/emqx/events")
public class EmqxUplinkController {

    /** 原始消息摄入用例。 */
    private final RawUplinkIngestionService ingestionService;

    /** @param ingestionService 原始消息摄入用例 */
    public EmqxUplinkController(RawUplinkIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * 接收 Broker 已认证设备的发布事件。
     *
     * @param request EMQX 规则回调
     * @return 是否写入 Kafka；身份/传输不合法或设备超限时返回 false，但 HTTP 始终成功以免 Broker 重试
     */
    @PostMapping("/message-published")
    @Operation(summary = "接收 MQTT 上行消息", description = "仅接受未超限的 v1 属性上报，并以 deviceId 为 key 写入原始 Kafka 主题；超限消息静默丢弃但不要求 Broker 断连或重试")
    public ResponseEntity<EmqxMessagePublishedResponse> messagePublished(
            @Valid @RequestBody EmqxMessagePublishedRequest request) {
        boolean accepted = ingestionService.ingest(request);
        return ResponseEntity.ok(new EmqxMessagePublishedResponse(accepted));
    }
}
