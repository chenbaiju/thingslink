package com.things.link.device.api.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.device.application.ApplicationMqttAccess;
import com.things.link.device.application.DeviceMqttAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "EMQX ACL 回调", description = "EMQX HTTP ACL 回调端点，校验设备对特定 Topic 的发布/订阅权限")
/**
 * EMQX HTTP ACL 回调。
 *
 * <p>Topic 结构为 {@code tc/v1/{projectKey}/{deviceKey}/{direction}/...} 。
 * 设备只能在自己的 {@code up/} 下发布、在 {@code down/} 下订阅。
 * 上下行分离是安全边界——混在同一子树中，被攻破的设备可伪造下行消息。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
public class EmqxAclController {

    /** Broker 内部订阅身份；其权限与设备 Topic 权限必须完全分离。 */
    private final BrokerIngressProperties ingressProperties;

    /** 应用票据权限与设备身份保持独立。 */
    private final ApplicationMqttAccess applications;
    /** 当前设备许可查询；内部及应用身份不进入此路径。 */
    private final DeviceMqttAccessService accessService;
    /** 仅供内部身份的最小装配；缺少设备范围查询时必须拒绝设备。 */
    public EmqxAclController(BrokerIngressProperties ingressProperties) {
        this.ingressProperties = ingressProperties;this.applications=null;this.accessService=null;
    }
    /** 生产装配强制提供当前设备范围，应用模块仍可选。 */
    @org.springframework.beans.factory.annotation.Autowired
    public EmqxAclController(BrokerIngressProperties ingressProperties,org.springframework.beans.factory.ObjectProvider<ApplicationMqttAccess> applications, DeviceMqttAccessService accessService){
        this.ingressProperties=ingressProperties;this.applications=applications.getIfAvailable();this.accessService=accessService;
    }

    /**
     * MQTT Topic 授权。
     * 校验设备上下行或应用票据精确Topic；应用仅允许QoS1订阅并复核当前资格
     *
     * @param body 请求 JSON 字段，由当前接口校验并解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<Map<String, String>>}
     */
    @PostMapping("/api/v1/emqx/acl")
    @Operation(summary = "MQTT Topic 授权", description = "校验设备上下行或应用票据精确Topic；应用仅允许QoS1订阅并复核当前资格")
    /** @param body EMQX 回调的 JSON 载荷，包含 username、topic、access、clientid */
    public ResponseEntity<Map<String, String>> acl(@RequestBody Map<String, Object> body) {
        String username = string(body, "username"), topic = string(body, "topic"),
               access = string(body, "access"); // "1" 表示订阅，"2" 表示发布

        if (username == null || topic == null) return deny();
        String zone=string(body,"zone");
        if(ApplicationMqttAccess.applicationIdentity(username)||ApplicationMqttAccess.ZONE.equals(zone)){
            return applications!=null&&applications.authorize(username,string(body,"clientid"),string(body,"peerhost"),zone,topic,access,string(body,"qos"))?allow():deny();
        }

        // 服务身份只允许订阅唯一内部 Topic，禁止发布以及访问任何设备上下行 Topic。
        if (ingressProperties.username().equals(username)) {
            boolean subscribe = "1".equals(access) || "subscribe".equals(access);
            return ingressProperties.enabled() && subscribe && BrokerIngressProperties.INTERNAL_TOPIC.equals(topic)
                    ? allow() : deny();
        }

        // 解析 username={projectKey}/{deviceKey}
        String[] parts = username.split("/", 2);
        if (parts.length != 2) return deny();
        String projectKey = parts[0], deviceKey = parts[1];

        // 解析 Topic: tc/v1/{projectKey}/{deviceKey}/{direction}/...
        String[] segments = topic.split("/");
        if (segments.length < 6
                || !segments[0].equals("tc") || !segments[1].equals("v1")
                || !segments[2].equals(projectKey) || !segments[3].equals(deviceKey))
            return deny();

        // Topic形状与凭据成功都不能替代当前平面；查询故障保持失败，禁止降级allow。
        var original = com.things.link.device.application.DeviceMqttIdentity.parse(body).orElse(null);
        if (accessService == null || !accessService.permits(projectKey, deviceKey, original,
                com.things.link.device.application.DeviceMqttIdentity.connectionId(body).orElse(null), string(body, "clientid"))) return deny();
        String direction = segments[4];

        // pub=2 只能在 up/ 下，sub=1 只能在 down/ 下
        if (("2".equals(access) || "publish".equals(access)) && "up".equals(direction)) return allow();
        if (("1".equals(access) || "subscribe".equals(access)) && "down".equals(direction)) return allow();

        return deny();
    }

    private static ResponseEntity<Map<String, String>> allow() { return ResponseEntity.ok(Map.of("result", "allow")); }
    private static ResponseEntity<Map<String, String>> deny() { return ResponseEntity.ok(Map.of("result", "deny")); }

    private static String string(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }
}
