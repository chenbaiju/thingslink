package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.BrokerHandoffEnvelope;
import com.things.link.ingestion.application.HandoffDisposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C4a-1c 资格记录器测试，锁定脱敏、跨重启编号和一次性 ACK 屏障。 */
class BrokerHandoffQualificationRecorderTests {

    /** 每个测试使用独立目录，避免证据编号相互污染。 */
    @TempDir
    private Path directory;

    /** 默认关闭必须允许空路径且不创建任何资格文件。 */
    @Test
    void disabledRecorderIsAConstantTimeNoOp() {
        BrokerHandoffQualificationRecorder recorder = new BrokerHandoffQualificationRecorder(
                new BrokerHandoffQualificationProperties(false, null, null, null), new ObjectMapper());

        recorder.initialize();
        recorder.record(envelope(), "raw", HandoffDisposition.ACCEPTED);
        recorder.recordPoison("secret".getBytes(StandardCharsets.UTF_8), HandoffDisposition.QUARANTINED);
        recorder.beforeManualAck(HandoffDisposition.ACCEPTED);

        assertThat(directory).isEmptyDirectory();
    }

    /** 证据只保留 payload 摘要，并在新记录器实例中恢复全局 sequence 与同 handoff attempt。 */
    @Test
    void appendsSanitizedEvidenceAndRestoresCountersAcrossRestart() throws Exception {
        Path evidence = directory.resolve("handoff.jsonl");
        Path scenario = directory.resolve("scenario.txt");
        Files.writeString(scenario, "normal\n", StandardCharsets.UTF_8);
        BrokerHandoffQualificationProperties properties = properties(evidence, scenario);
        ObjectMapper objectMapper = new ObjectMapper();
        BrokerHandoffQualificationRecorder first = new BrokerHandoffQualificationRecorder(properties, objectMapper);
        first.initialize();

        first.record(envelope(), "raw", HandoffDisposition.ACCEPTED);
        BrokerHandoffQualificationRecorder restarted = new BrokerHandoffQualificationRecorder(properties, objectMapper);
        restarted.initialize();
        // 场景文件只标记故障窗口；同一 durable handoff 跨窗口重放仍必须沿用全局 attempt。
        Files.writeString(scenario, "emqx-restart\n", StandardCharsets.UTF_8);
        restarted.record(envelope(), "raw", HandoffDisposition.DUPLICATE);

        List<String> lines = Files.readAllLines(evidence, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2).noneMatch(line -> line.contains("plain-secret"));
        JsonNode firstRow = objectMapper.readTree(lines.get(0));
        JsonNode secondRow = objectMapper.readTree(lines.get(1));
        assertThat(firstRow.get("sequence").asLong()).isEqualTo(1L);
        assertThat(firstRow.get("attempt").asInt()).isEqualTo(1);
        assertThat(firstRow.get("payloadSha256").asText()).isEqualTo(sha256("plain-secret"));
        assertThat(firstRow.get("businessMessageId").isNull()).isTrue();
        assertThat(firstRow.get("result").asText()).isEqualTo("accepted");
        assertThat(secondRow.get("sequence").asLong()).isEqualTo(2L);
        assertThat(secondRow.get("scenario").asText()).isEqualTo("emqx-restart");
        assertThat(secondRow.get("attempt").asInt()).isEqualTo(2);
        assertThat(secondRow.get("result").asText()).isEqualTo("duplicate");
    }

    /** 首次强杀留下的屏障标记必须让重启重放立即越过，不能再次进入 30 秒等待循环。 */
    @Test
    void existingAckBarrierIsOneShotAndAllowsReplayToContinue() throws Exception {
        Path scenario = directory.resolve("scenario.txt");
        Files.writeString(scenario, "ack-ambiguity\n", StandardCharsets.UTF_8);
        BrokerHandoffQualificationProperties properties = properties(directory.resolve("handoff.jsonl"), scenario);
        Files.writeString(properties.ackBarrierPath(), "durable-dispatch-recorded\n", StandardCharsets.UTF_8);
        BrokerHandoffQualificationRecorder recorder = new BrokerHandoffQualificationRecorder(
                properties, new ObjectMapper());
        recorder.initialize();

        recorder.beforeManualAck(HandoffDisposition.ACCEPTED);

        assertThat(Files.readString(properties.ackBarrierPath())).isEqualTo("durable-dispatch-recorded\n");
    }

    /** 启用资格时缺路径或路径别名必须在启动阶段 fail-closed。 */
    @Test
    void rejectsMissingOrAliasedQualificationPaths() {
        ObjectMapper objectMapper = new ObjectMapper();
        assertThatThrownBy(() -> new BrokerHandoffQualificationRecorder(
                new BrokerHandoffQualificationProperties(true, null, null, null), objectMapper).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("三个路径均必填");
        Path same = directory.resolve("same");
        assertThatThrownBy(() -> new BrokerHandoffQualificationRecorder(
                new BrokerHandoffQualificationProperties(true, same, same, directory.resolve("barrier")),
                objectMapper).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("互不相同");
    }

    /** @return 指向测试隔离目录的启用配置 */
    private BrokerHandoffQualificationProperties properties(Path evidence, Path scenario) {
        return new BrokerHandoffQualificationProperties(true, evidence, scenario,
                directory.resolve("ack-barrier.marker"));
    }

    /** @return 含敏感 payload 的合法信封，证据不得保留其明文 */
    private static BrokerHandoffEnvelope envelope() {
        String payload = java.util.Base64.getEncoder().encodeToString(
                "plain-secret".getBytes(StandardCharsets.UTF_8));
        return new BrokerHandoffEnvelope("handoff-1", "project/device",
                "tc/v1/project/device/up/property/report", payload, 1, false,
                "device-client", 1234L, "emqx@127.0.0.1");
    }

    /** 合法 JSON payload 的 messageId 必须进入脱敏证据，供机器裁决绑定最终事实。 */
    @Test
    void recordsBusinessMessageIdFromLegalPayload() throws Exception {
        Path evidence = directory.resolve("business-message.jsonl");
        Path scenario = directory.resolve("business-scenario.txt");
        Files.writeString(scenario, "normal\n", StandardCharsets.UTF_8);
        BrokerHandoffQualificationRecorder recorder = new BrokerHandoffQualificationRecorder(
                properties(evidence, scenario), new ObjectMapper());
        recorder.initialize();
        String json = "{\"messageId\":\"019d3000-0000-7000-8000-000000000001\",\"payload\":{}}";
        BrokerHandoffEnvelope envelope = new BrokerHandoffEnvelope("handoff-business", "project/device",
                "tc/v1/project/device/up/property/report",
                java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                1, false, "device-client", 1234L, "emqx@127.0.0.1");

        recorder.record(envelope, "raw", HandoffDisposition.ACCEPTED);

        JsonNode row = new ObjectMapper().readTree(Files.readString(evidence, StandardCharsets.UTF_8));
        assertThat(row.get("businessMessageId").asText())
                .isEqualTo("019d3000-0000-7000-8000-000000000001");
    }

    /** @return 测试期固定小写 SHA-256 */
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
