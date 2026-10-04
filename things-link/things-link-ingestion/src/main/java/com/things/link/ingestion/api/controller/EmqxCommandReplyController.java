package com.things.link.ingestion.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.ingestion.api.dto.response.EmqxMessagePublishedResponse;
import com.things.link.ingestion.application.CommandReplyIngestionService;
import com.things.link.support.tenant.DataPlaneDatabase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EMQX 命令回复专用入口。
 *
 * <p>EMQX 5 Webhook Connector 会保存但不转发 URL 查询串，所以不能通过
 * {@code message-published?kind=command-reply} 分流：它会静默落到通用上行入口并返回 200。
 * 独立路径必须同步登记到 BrokerCallbackAuthenticationFilter，并由集成测试证明无共享密钥时 fail-closed。</p>
 */
@Hidden
@DataPlaneDatabase
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/emqx/events")
public class EmqxCommandReplyController {

    /** 命令回复摄入用例。 */
    private final CommandReplyIngestionService replyService;

    /** @param replyService 命令回复摄入用例 */
    public EmqxCommandReplyController(CommandReplyIngestionService replyService) {
        this.replyService = replyService;
    }

    /**
     * 接收专用 EMQX 规则转发的命令回复。
     *
     * @param request Broker 已观察到的认证身份、Topic 与原始载荷
     * @return 是否通过协议与身份校验；HTTP 始终 200，防止永久错误被 Broker 放大重试
     */
    @PostMapping("/command-reply")
    public ResponseEntity<EmqxMessagePublishedResponse> commandReply(
            @Valid @RequestBody EmqxMessagePublishedRequest request) {
        return ResponseEntity.ok(new EmqxMessagePublishedResponse(replyService.ingest(request)));
    }
}
