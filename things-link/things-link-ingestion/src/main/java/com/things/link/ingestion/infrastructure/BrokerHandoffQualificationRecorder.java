package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.BrokerHandoffEnvelope;
import com.things.link.ingestion.application.HandoffDisposition;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * C4a-1c 同步逐 handoff 证据记录器。
 *
 * <p>资格启用时，记录必须在 MQTT 手动确认 前写入并 {@link FileChannel#force(boolean)}；证据写失败会向上抛出，
 * 从而保留 Broker 未确认消息。正常生产关闭时所有方法均为常数时间空操作。</p>
 */
@Component
public class BrokerHandoffQualificationRecorder {

    /** 裁决器冻结的七场景名称，控制文件不得注入任意标签。 */
    private static final Set<String> SCENARIOS = Set.of("normal", "application-stop", "kafka-stop",
            "database-stop", "ack-ambiguity", "emqx-restart", "poison");
    /** JSONL 固定 UTF-8 换行。 */
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    /** 资格配置。 */
    private final BrokerHandoffQualificationProperties properties;
    /** Boot 统一 JSON 编码器。 */
    private final ObjectMapper objectMapper;
    /** 同一进程内按 handoffId 全局连续编号；场景切换不得重置同一持久交接的身份。 */
    private final Map<String, Integer> attempts = new HashMap<>();
    /** 跨重启恢复的下一 sequence。 */
    private long sequence;

    /** @param properties 资格路径 @param objectMapper JSON 编码器 */
    public BrokerHandoffQualificationRecorder(BrokerHandoffQualificationProperties properties,
                                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 启用时验证路径并从既有 JSONL 恢复 sequence/attempt，防止应用重启后证据从 1 重新开始。 */
    @PostConstruct
    public synchronized void initialize() {
        properties.requireValidWhenEnabled();
        if (!properties.enabled()) return;
        Path evidence = normalized(properties.evidencePath());
        try {
            Path parent = evidence.getParent();
            if (parent == null) throw new IllegalStateException("handoff evidence 必须有父目录");
            Files.createDirectories(parent);
            if (!Files.exists(evidence)) return;
            for (String line : Files.readAllLines(evidence, StandardCharsets.UTF_8)) {
                if (line.isBlank()) throw new IllegalStateException("handoff evidence 含空行");
                JsonNode row = objectMapper.readTree(line);
                long currentSequence = requiredLong(row, "sequence");
                String scenario = requiredText(row, "scenario");
                String handoffId = requiredText(row, "handoffId");
                int attempt = Math.toIntExact(requiredLong(row, "attempt"));
                int expectedAttempt = attempts.getOrDefault(handoffId, 0) + 1;
                if (currentSequence != sequence + 1 || !SCENARIOS.contains(scenario)
                        || attempt != expectedAttempt) {
                    throw new IllegalStateException("handoff evidence sequence/attempt 不连续");
                }
                sequence = currentSequence;
                attempts.put(handoffId, attempt);
            }
        } catch (IOException | ArithmeticException exception) {
            throw new IllegalStateException("无法恢复 handoff qualification evidence", exception);
        }
    }

    /** 记录合法信封的持久分派结果；payload 只保存 SHA-256。 */
    public synchronized void record(BrokerHandoffEnvelope envelope, String dispatchType,
                                    HandoffDisposition disposition) {
        if (!properties.enabled()) return;
        byte[] payload = Base64.getDecoder().decode(envelope.payloadBase64());
        append(envelope.handoffId(), businessMessageId(payload), sha256(payload), dispatchType, disposition);
    }

    /** 无法解析的 poison 没有可信 handoffId，使用固定前缀加信封摘要形成脱敏证据身份。 */
    public synchronized void recordPoison(byte[] envelopeBytes, HandoffDisposition disposition) {
        if (!properties.enabled()) return;
        String digest = sha256(envelopeBytes == null ? new byte[0] : envelopeBytes);
        append("poison:" + digest, null, digest, "poison", disposition);
    }

    /**
     * ACK 歧义场景在下游持久事实与证据均落盘后建立标记，然后等待外部执行器强杀进程。
     *
     * <p>若执行器没有在 30 秒内杀死进程则抛错且不 ACK，避免资格 seam 自己制造假成功。</p>
     */
    public void beforeManualAck(HandoffDisposition disposition) {
        if (!properties.enabled() || disposition != HandoffDisposition.ACCEPTED
                || !"ack-ambiguity".equals(currentScenario())) return;
        Path barrier = normalized(properties.ackBarrierPath());
        try {
            // 首次强杀会留下标记；重启重放必须越过同一屏障，才能完成第二次 ACK。
            if (Files.exists(barrier)) return;
            Files.writeString(barrier, "durable-dispatch-recorded\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            for (int attempt = 0; attempt < 300; attempt++) {
                Thread.sleep(100L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ACK qualification 屏障等待被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法建立 ACK qualification 屏障", exception);
        }
        throw new IllegalStateException("ACK qualification 执行器未在 30 秒内强杀应用");
    }

    /** 追加单行并强制同步数据与元数据；任何失败都会阻止 MQTT ACK。 */
    private void append(String handoffId, String businessMessageId, String payloadSha256, String dispatchType,
                        HandoffDisposition disposition) {
        String scenario = currentScenario();
        int attempt = attempts.getOrDefault(handoffId, 0) + 1;
        EvidenceRow row = new EvidenceRow(++sequence, scenario, handoffId, businessMessageId,
                payloadSha256, dispatchType,
                attempt, disposition.name().toLowerCase(java.util.Locale.ROOT), Instant.now().toString());
        try {
            byte[] json = objectMapper.writeValueAsBytes(row);
            Path evidence = normalized(properties.evidencePath());
            try (FileChannel channel = FileChannel.open(evidence, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, ByteBuffer.wrap(json));
                writeFully(channel, ByteBuffer.wrap(NEWLINE));
                channel.force(true);
            }
            attempts.put(handoffId, attempt);
        } catch (IOException exception) {
            sequence--;
            throw new IllegalStateException("handoff qualification evidence 写入失败", exception);
        }
    }

    /** 每次记录都读取控制文件，使同一应用进程可以安全切换场景且无需动态配置接口。 */
    private String currentScenario() {
        try {
            String scenario = Files.readString(normalized(properties.scenarioPath()), StandardCharsets.UTF_8).strip();
            if (!SCENARIOS.contains(scenario)) throw new IllegalStateException("handoff qualification 场景非法");
            return scenario;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 handoff qualification 场景", exception);
        }
    }

    /** @return 绝对规范路径 */
    private static Path normalized(Path value) {
        return value.toAbsolutePath().normalize();
    }

    /** FileChannel 允许短写；资格证据必须完整写入后才可执行 force 与 MQTT ACK。 */
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    /** 读取恢复所需非空文本。 */
    private static String requiredText(JsonNode row, String name) {
        JsonNode value = row.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("handoff evidence 缺少 " + name);
        }
        return value.asText();
    }

    /** 读取恢复所需整数。 */
    private static long requiredLong(JsonNode row, String name) {
        JsonNode value = row.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException("handoff evidence 缺少 " + name);
        }
        return value.asLong();
    }

    /** @return 小写 SHA-256 */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /**
     * 从合法业务 payload 提取 messageId，建立场景 receipt 与生产入口证据的机器可校验关联。
     *
     * <p>解析失败保持 {@code null}：业务分派仍按原实现判定，资格裁决会因 expected messageId 未绑定而 fail-closed，
     * 不能为了测试证据改变生产接受语义。</p>
     */
    private String businessMessageId(byte[] payload) {
        try {
            JsonNode value = objectMapper.readTree(payload);
            JsonNode messageId = value == null ? null : value.get("messageId");
            return messageId != null && messageId.isTextual() && !messageId.asText().isBlank()
                    ? messageId.asText() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 裁决器冻结的逐 handoff JSONL 行。 */
    private record EvidenceRow(long sequence, String scenario, String handoffId, String businessMessageId,
                               String payloadSha256,
                               String dispatchType, int attempt, String result, String wallClock) {
    }
}
