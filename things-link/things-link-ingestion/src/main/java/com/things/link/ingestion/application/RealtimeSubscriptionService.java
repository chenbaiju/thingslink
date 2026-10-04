package com.things.link.ingestion.application;

import com.things.link.shared.id.Uuid7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 解析控制台 WebSocket 文本协议，并在每次订阅时重新执行项目与设备确权。
 *
 * <p>握手 JWT 只证明连接建立时的身份；项目成员可能在连接存续期被移除。因此 {@code SUBSCRIBE}
 * 不能只检查 token 中的 pid，而要恢复当前线程租户范围后调用项目与设备公开应用端口复验。</p>
 */
@Service
public class RealtimeSubscriptionService {

    /** 统一 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** 本机订阅及有界发送队列。 */
    private final RealtimeSubscriptionRegistry registry;

    /** 项目成员与设备归属的统一权威复核。 */
    private final RealtimeAuthorizationService authorizationService;

    /** JWT到期判断使用的UTC时钟。 */
    private final Clock clock;

    /**
     * @param objectMapper JSON 编解码器
     * @param registry 本机会话注册表
     * @param authorizationService 项目成员与设备归属权威复核
     */
    @Autowired
    public RealtimeSubscriptionService(ObjectMapper objectMapper, RealtimeSubscriptionRegistry registry,
                                       RealtimeAuthorizationService authorizationService) {
        this(objectMapper, registry, authorizationService, Clock.systemUTC());
    }

    /**
     * 可替换时钟的内部构造器，只用于确定性验证JWT截止边界。
     *
     * @param objectMapper JSON编解码器
     * @param registry 本机会话注册表
     * @param authorizationService 项目成员与设备归属权威复核
     * @param clock UTC时钟
     */
    RealtimeSubscriptionService(ObjectMapper objectMapper, RealtimeSubscriptionRegistry registry,
                                RealtimeAuthorizationService authorizationService, Clock clock) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    /**
     * 处理一条浏览器文本消息。
     *
     * @param connection 当前已认证会话
     * @param text 原始 UTF-8 文本帧
     */
    public void handle(RealtimeConnection connection, String text) {
        if (connection.principal().expiredAt(clock.instant())) {
            registry.revoke(connection.id(), 1008, "realtime token expired");
            return;
        }
        if ("PING".equals(text)) {
            registry.sendControl(connection.id(), "PONG");
            return;
        }
        try {
            ClientFrame frame = objectMapper.readValue(text, ClientFrame.class);
            if ("PING".equals(frame.type())) {
                registry.sendControl(connection.id(), objectMapper.writeValueAsString(new PongFrame("PONG")));
                return;
            }
            if (!"SUBSCRIBE".equals(frame.type())) {
                throw new IllegalArgumentException("不支持的实时消息类型");
            }
            Map<UUID, Set<String>> subscriptions = parseSubscriptions(frame.subscriptions());
            reauthorizeAndReplace(connection, subscriptions);
            registry.sendControl(connection.id(), objectMapper.writeValueAsString(
                    new SubscribedFrame("SUBSCRIBED", frame.requestId(), Uuid7.generate().toString(),
                            subscriptionCount(subscriptions))));
        } catch (SessionRevokedException revoked) {
            // 注销已清空控制队列并关闭连接，不能再排入会掩盖安全失效的普通错误帧。
        } catch (Exception exception) {
            // 协议错误仅回显稳定分类，绝不把 Jackson、数据库或令牌异常文本暴露给浏览器。
            try {
                registry.sendControl(connection.id(), objectMapper.writeValueAsString(
                        new ErrorFrame("ERROR", "INVALID_SUBSCRIPTION")));
            } catch (Exception ignored) {
                registry.sendControl(connection.id(), "{\"type\":\"ERROR\",\"code\":\"INVALID_SUBSCRIPTION\"}");
            }
        }
    }

    /** 在显式租户范围中复验成员资格、设备归属并原子替换订阅。 */
    private void reauthorizeAndReplace(RealtimeConnection connection, Map<UUID, Set<String>> subscriptions) {
        RealtimePrincipal principal = connection.principal();
        try {
            authorizationService.requireProjectAccess(principal);
        } catch (RealtimeProjectAccessDeniedException denied) {
            registry.revoke(connection.id(), 1008, "realtime authorization revoked");
            throw new SessionRevokedException(denied);
        }
        // 成功权威项目复核续十秒发送截止；后续设备错误仍只影响本次订阅请求。
        registry.refreshAuthorization(connection.id());
        authorizationService.requireSubscriptionAccess(principal, subscriptions.keySet());
        registry.replaceSubscriptions(connection.id(), subscriptions);
    }

    /** 将请求数组折叠成不可变 device → propertyKeys 索引，重复键即幂等。 */
    private static Map<UUID, Set<String>> parseSubscriptions(List<SubscriptionRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("订阅不能为空");
        }
        Map<UUID, Set<String>> result = new LinkedHashMap<>();
        for (SubscriptionRequest request : requested) {
            if (request == null || request.deviceId() == null || request.propertyKeys() == null
                    || request.propertyKeys().isEmpty()) {
                throw new IllegalArgumentException("订阅设备与属性不能为空");
            }
            Set<String> properties = result.computeIfAbsent(request.deviceId(), ignored -> new LinkedHashSet<>());
            properties.addAll(request.propertyKeys());
        }
        return result;
    }

    /** @param subscriptions 订阅索引 @return 唯一 device × property 数 */
    private static int subscriptionCount(Map<UUID, Set<String>> subscriptions) {
        return subscriptions.values().stream().mapToInt(Set::size).sum();
    }

    /** 浏览器请求的 JSON 协议。 */
    private record ClientFrame(String type, String requestId, List<SubscriptionRequest> subscriptions) {
    }

    /** 同一设备可以在一条 SUBSCRIBE 中承载多个属性键。 */
    private record SubscriptionRequest(UUID deviceId, List<String> propertyKeys) {
    }

    /** 订阅成功响应；服务端生成 ID，客户端请求 ID 仅作关联。 */
    private record SubscribedFrame(String type, String requestId, String subscriptionId, int subscriptionCount) {
    }

    /** JSON PING 的心跳响应。 */
    private record PongFrame(String type) {
    }

    /** 统一但不泄露内部细节的协议错误。 */
    private record ErrorFrame(String type, String code) {
    }

    /** 标记本次消息已经按安全失权收束，外层不得再发送普通协议错误。 */
    private static final class SessionRevokedException extends RuntimeException {
        /** @param cause 确定项目失权 */
        private SessionRevokedException(RuntimeException cause) {
            super(cause);
        }
    }
}
