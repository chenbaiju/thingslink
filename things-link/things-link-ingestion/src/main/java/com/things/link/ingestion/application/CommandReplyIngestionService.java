package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.application.DeviceAccessScopeService.ResolvedDeviceAccessScope;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.application.DeviceCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 解析已认证的 MQTT 命令回复，并调用 telemetry application 推进状态机。
 *
 * <p>commandId 只从 Broker 实际观察到的 Topic 读取；payload 即使增加同名字段也不会被采用。项目、租户与
 * connectionDeviceId 同样由 username + Topic 经 device application 确权，禁止设备自报归属。</p>
 */
@Service
public class CommandReplyIngestionService {

    /** 记录幂等结果时不打印 output、厂商错误消息或原始字节。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandReplyIngestionService.class);
    /** 回复 messageType 固定为 command/{UUIDv7}/reply。 */
    private static final String COMMAND_PREFIX = "command/";
    /** 回复 messageType 固定尾段。 */
    private static final String REPLY_SUFFIX = "/reply";
    /** 属性设置回复固定 Topic；稳定 requestId 从受限 payload 读取并作为命令 ID。 */
    private static final String PROPERTY_SET_REPLY = "property/set/reply";
    /** 数据库与 API 冻结的设备错误码最大长度。 */
    private static final int MAX_ERROR_CODE_LENGTH = 64;
    /** 数据库与 API 冻结的设备诊断最大长度。 */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** 设备域可信身份解析端口。 */
    private final DeviceAccessScopeService scopeService;
    /** 命令状态机 application 端口。 */
    private final DeviceCommandService commandService;
    /** Boot 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param scopeService 设备接入身份解析服务
     * @param commandService 命令状态机公共入口
     * @param objectMapper 统一 JSON 映射器
     */
    public CommandReplyIngestionService(DeviceAccessScopeService scopeService,
                                        DeviceCommandService commandService,
                                        ObjectMapper objectMapper) {
        this.scopeService = scopeService;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 Broker 元数据、回复 Topic 与冻结 JSON 后推进命令状态。
     *
     * @param request EMQX message.publish 回调
     * @return 报文是否通过协议与身份校验；重复或乱序回复仍返回 true，避免 Broker 无意义重试
     */
    public boolean ingest(EmqxMessagePublishedRequest request) {
        return ingestHandoff(request) != HandoffDisposition.PERMANENT_REJECT;
    }

    /**
     * 推进命令事实并区分幂等吸收；数据库异常保持抛出，以便 MQTT 会话重放。
     *
     * @param request Broker 持久信封还原出的命令回复
     * @return 可确认的接纳、重复或永久拒绝分类
     */
    public HandoffDisposition ingestHandoff(EmqxMessagePublishedRequest request) {
        Optional<MqttUplinkTopic> parsedTopic = MqttUplinkTopic.parse(request.topic());
        if (parsedTopic.isEmpty() || !parsedTopic.orElseThrow().belongsTo(request.username())
                || request.qos() != 1 || request.retained() || request.publishedAtMs() < 0) {
            return rejectHandoff("envelope_invalid");
        }
        ReplyPayload payload = parsePayload(request.payloadBase64()).orElse(null);
        if (payload == null || !validReply(payload)) {
            return rejectHandoff("payload_invalid");
        }
        String messageType = parsedTopic.orElseThrow().messageType();
        UUID commandId = PROPERTY_SET_REPLY.equals(messageType)
                ? payload.requestId() : commandId(messageType).orElse(null);
        if (commandId == null || commandId.version() != 7) return rejectHandoff("command_id_invalid");
        Optional<ResolvedDeviceAccessScope> resolved = scopeService.resolve(
                parsedTopic.orElseThrow().projectKey(), parsedTopic.orElseThrow().deviceKey());
        if (resolved.isEmpty()) {
            return rejectHandoff("device_scope_missing");
        }
        ResolvedDeviceAccessScope scope = resolved.orElseThrow();
        String outputJson = payload.output() == null ? "{}" : objectMapper.writeValueAsString(payload.output());
        DeviceCommandReply reply = new DeviceCommandReply(payload.messageId(), scope.tenantId(), scope.projectId(),
                scope.deviceId(), commandId, payload.occurredAt(), Instant.ofEpochMilli(request.publishedAtMs()),
                payload.status(), outputJson, normalize(payload.errorCode()), normalize(payload.message()),
                TraceContext.resolve(TraceContext.current()));
        boolean changed = commandService.applyReply(reply);
        LOGGER.debug("设备命令回复已处理 commandId={} messageId={} status={} changed={}",
                commandId, payload.messageId(), payload.status(), changed);
        // false 只代表重复、终态或命令不存在，不是可通过重投恢复的协议失败。
        return changed ? HandoffDisposition.ACCEPTED : HandoffDisposition.DUPLICATE;
    }

    /** 记录固定拒绝原因并返回 durable ingress 的永久拒绝分类。 */
    private static HandoffDisposition rejectHandoff(String reason) {
        reject(reason);
        return HandoffDisposition.PERMANENT_REJECT;
    }

    /**
     * 在显式开启 DEBUG 排障时记录不含 Topic、payload、设备身份或厂商诊断的稳定拒绝分类。
     * 默认级别不能逐条 WARN：已认证设备仍可能持续发送非法回复，WARN 会把协议错误放大成日志洪泛。
     *
     * @param reason 固定低基数拒绝原因
     * @return 固定 false，供回调响应表达未接纳
     */
    private static boolean reject(String reason) {
        LOGGER.debug("设备命令回复被拒绝 reason={}", reason);
        return false;
    }

    /** 从严格三段 messageType 中取得 commandId，其他 up/command 路径一律拒绝。 */
    private static Optional<UUID> commandId(String messageType) {
        if (messageType == null || !messageType.startsWith(COMMAND_PREFIX) || !messageType.endsWith(REPLY_SUFFIX)) {
            return Optional.empty();
        }
        String value = messageType.substring(COMMAND_PREFIX.length(), messageType.length() - REPLY_SUFFIX.length());
        try {
            UUID id = UUID.fromString(value);
            return id.version() == 7 ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Base64 与 JSON 都来自不可信设备；解析失败只返回空，不把原始内容带进日志。 */
    private Optional<ReplyPayload> parsePayload(String payloadBase64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payloadBase64);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(bytes, ReplyPayload.class));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /** UUIDv7、时间、状态和数据库字段长度在进入 telemetry 事务前一次校验。 */
    private static boolean validReply(ReplyPayload payload) {
        if (payload.messageId() == null || payload.messageId().version() != 7
                || payload.occurredAt() == null || payload.status() == null
                || (payload.output() != null && !payload.output().isObject())) {
            return false;
        }
        return lengthAtMost(payload.errorCode(), MAX_ERROR_CODE_LENGTH)
                && lengthAtMost(payload.message(), MAX_MESSAGE_LENGTH);
    }

    /** 空白诊断归一化为 null，避免数据库里出现无法区分的空字符串。 */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Unicode 码点长度检查，避免补充平面字符被 UTF-16 误算为两个字符。 */
    private static boolean lengthAtMost(String value, int maximum) {
        return value == null || value.codePointCount(0, value.length()) <= maximum;
    }

    /**
     * ADR 0021 冻结的设备回复载荷。
     *
     * @param messageId 设备生成的 UUIDv7 幂等键
     * @param occurredAt 设备首次处理该阶段的发生时间
     * @param status ACK/SUCCESS/FAILED
     * @param output 输出对象；省略时按空对象处理
     * @param errorCode 失败诊断码
     * @param message 失败诊断摘要
     */
    private record ReplyPayload(UUID messageId, UUID requestId, Instant occurredAt, DeviceCommandReply.Status status,
                                JsonNode output, String errorCode, String message) {
    }
}
