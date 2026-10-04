package com.things.link.device.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.device.infrastructure.emqx.EmqxAuthService;
import com.things.link.device.application.ApplicationMqttAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * EMQX HTTP 认证回调。EMQX 5.x HTTP auth 插件在设备连接时 POST 到此端点。
 * 此端点<b>不走 Spring Security 认证</b>——调用方是 EMQX 集群，而非浏览器。
 */
@Tag(name = "EMQX 认证回调", description = "EMQX HTTP 认证回调端点，由 MQTT Broker 在设备连接时调用，不走用户认证")
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
public class EmqxAuthController {
    /** 设备认证服务。 */ private final EmqxAuthService service;
    /** @param service 设备认证服务 */
    private final ApplicationMqttAccess applications;
    public EmqxAuthController(EmqxAuthService service) { this.service = service;this.applications=null; }
    @org.springframework.beans.factory.annotation.Autowired
    public EmqxAuthController(EmqxAuthService service,org.springframework.beans.factory.ObjectProvider<ApplicationMqttAccess> applications){
        this.service=service;this.applications=applications.getIfAvailable();
    }

    @PostMapping("/api/v1/emqx/auth")
    @Operation(summary = "MQTT 客户端认证", description = "校验设备、durable ingress或独立应用票据；应用必须有受信zone及peerhost")
    /** @param body EMQX 回调的 JSON 载荷，包含 username、password、clientid */
    public ResponseEntity<Map<String, Object>> auth(@RequestBody Map<String, Object> body) {
        String username = string(body, "username"), password = string(body, "password"),
               clientid = string(body, "clientid");
        String zone=string(body,"zone");
        if(ApplicationMqttAccess.applicationIdentity(username)||ApplicationMqttAccess.ZONE.equals(zone)){
            var result=applications==null?ApplicationMqttAccess.Authentication.deny():applications.authenticate(username,password,clientid,string(body,"peerhost"),zone);
            Map<String,Object> response=new LinkedHashMap<>();response.put("result",result.allowed()?"allow":"deny");response.put("is_superuser",false);
            if(result.allowed())response.put("expire_at",result.expiresAt().getEpochSecond());
            return ResponseEntity.ok(response);
        }
        var authentication = service.authenticateIdentity(username, password, clientid);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", authentication.result() == EmqxAuthService.EmqxAuthResult.ALLOW ? "allow" : "deny");
        response.put("is_superuser", false);
        var identity = authentication.identity();
        if (identity != null) {
            var session = new com.things.link.device.application.DeviceMqttSessionIdentity(
                    new com.things.link.device.application.DeviceMqttIdentity(identity, authentication.configVersion()), clientid);
            var attributes = new LinkedHashMap<>(session.attributes());
            attributes.put("tc_auth_connection_id", authentication.connectionId().toString());
            response.put("client_attrs", attributes);
            response.put("clientid_override", session.effectiveClientId());
        } else if (authentication.result() == EmqxAuthService.EmqxAuthResult.ALLOW) {
            // 内部ingress固定会话不进入任何设备命名空间，也不覆盖其Client ID。
            response.put("client_attrs", Map.of("tc_auth_mountpoint", ""));
        }
        return ResponseEntity.ok(response);
    }

    /** @return 请求字段的字符串值，类型不符时按缺失处理并拒绝认证 */
    private static String string(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }
}
