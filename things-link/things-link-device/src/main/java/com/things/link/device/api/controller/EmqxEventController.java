package com.things.link.device.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.device.infrastructure.emqx.EmqxConnectionEventService;
import com.things.link.support.tenant.DataPlaneDatabase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * EMQX 规则引擎连接事件回调；由 Broker 调用，不依赖控制台用户登录态。
 *
 * <p>S3-11F 已将 connected/disconnected 两条规则固化到 {@code deploy/emqx/base.hocon}；
 * 此处保持精简的 Broker 内部契约，不暴露为控制台业务接口。</p>
 */
@Tag(name = "EMQX 连接事件", description = "EMQX 规则引擎在设备连接/断开时回调，记录在线状态和连接历史")
@DataPlaneDatabase
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/emqx/events")
public class EmqxEventController {
    /** 连接事件服务。 */ private final EmqxConnectionEventService service;

    /** @param service 连接事件服务 */
    public EmqxEventController(EmqxConnectionEventService service) { this.service = service; }

    /**
     * 设备上线。
     *
     * @param body EMQX client.connected 事件
     * @return 是否处理成功
     */
    @PostMapping("/connected")
    @Operation(summary = "设备上线", description = "EMQX 在设备 CONNECT 成功后回调，记录连接并更新设备状态为 ONLINE")
    public ResponseEntity<Map<String, Boolean>> connected(@RequestBody Map<String, Object> body) {
        boolean accepted = service.connected(string(body, "username"), string(body, "clientid"),
                firstString(body, "peerhost", "client_ip"), firstString(body, "node", "node_name"),
                com.things.link.device.application.DeviceMqttIdentity.parse(body).orElse(null),
                com.things.link.device.application.DeviceMqttIdentity.connectionId(body).orElse(null));
        return ResponseEntity.ok(Map.of("accepted", accepted));
    }

    /**
     * 设备下线。
     *
     * @param body EMQX client.disconnected 事件
     * @return 是否处理成功
     */
    @PostMapping("/disconnected")
    @Operation(summary = "设备下线", description = "EMQX 在设备断开连接时回调，记录断开会话并更新设备状态为 OFFLINE")
    public ResponseEntity<Map<String, Boolean>> disconnected(@RequestBody Map<String, Object> body) {
        boolean accepted = service.disconnected(string(body, "username"), string(body, "clientid"),
                firstString(body, "reason", "disconnect_reason"),
                com.things.link.device.application.DeviceMqttIdentity.parse(body).orElse(null),
                com.things.link.device.application.DeviceMqttIdentity.connectionId(body).orElse(null));
        return ResponseEntity.ok(Map.of("accepted", accepted));
    }

    /** @return 指定键的字符串值 */
    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String text ? text : null;
    }

    /** @return 第一个存在的兼容字段值 */
    private static String firstString(Map<String, Object> body, String first, String second) {
        String value = string(body, first);
        return value != null ? value : string(body, second);
    }
}
