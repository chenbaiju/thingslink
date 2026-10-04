package com.things.link.ingestion.application;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 解析架构文档 5.3 冻结的 MQTT v1 上行 Topic。
 *
 * <p>S3.5-3 将 EMQX 入口扩为 {@code up/#} 后，接入确权与 raw 消费分派必须共用同一套层级校验；
 * 两处各写一份会在新增事件、命令响应或拓扑消息时产生不一致，最终表现为 Broker 已接收但消费者无法分类。</p>
 *
 * @param projectKey Topic 中的项目短标识
 * @param deviceKey Topic 中的设备短标识
 * @param messageType {@code up/} 后的消息类型路径，例如 {@code property/report} 或 {@code event/alarm}
 */
record MqttUplinkTopic(String projectKey, String deviceKey, String messageType) {

    /** Topic 每一层的安全字符集；禁止斜杠进入标识符，否则会改变 ACL 与路由层级。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    /**
     * 解析至少包含一个消息类型层级的 v1 上行 Topic。
     *
     * @param topic Broker 观察到的原始 Topic
     * @return 合法 Topic；方向、层级或字符集不满足冻结契约时为空
     */
    static Optional<MqttUplinkTopic> parse(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        String[] segments = topic.split("/", -1);
        if (segments.length < 6 || !"tc".equals(segments[0]) || !"v1".equals(segments[1])
                || !"up".equals(segments[4])) {
            return Optional.empty();
        }
        for (int index = 2; index < segments.length; index++) {
            if (index == 4) {
                continue;
            }
            if (!IDENTIFIER.matcher(segments[index]).matches()) {
                return Optional.empty();
            }
        }
        String messageType = String.join("/", Arrays.copyOfRange(segments, 5, segments.length));
        return Optional.of(new MqttUplinkTopic(segments[2], segments[3], messageType));
    }

    /**
     * 同时核对认证用户名，禁止设备把合法 Topic 结构用于其他设备身份。
     *
     * @param username EMQX 已认证用户名，格式为 {@code projectKey/deviceKey}
     * @return 用户名是否与 Topic 身份完全一致
     */
    boolean belongsTo(String username) {
        return (projectKey + "/" + deviceKey).equals(username);
    }
}
