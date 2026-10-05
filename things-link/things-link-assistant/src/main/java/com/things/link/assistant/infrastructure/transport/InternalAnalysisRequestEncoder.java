package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.PreparedModelEvidence.Input;
import com.things.link.assistant.domain.AnalysisCall;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** 固定业务内部报文编码；仅读取本模块受控对象，不提供权限、密钥或发送许可。 */
public final class InternalAnalysisRequestEncoder {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_INPUT_BYTES = 16 * 1024;
    private static final int MAX_INTERNAL_BYTES = 24 * 1024;

    private InternalAnalysisRequestEncoder() {}

    /**
     * 从已派发元数据与专用证据投影编码固定内部报文，不序列化完整领域对象。
     * 编码成功不证明当前权限、真实持久记录或执行认领，调用器必须另行复核。
     * @param call 原调用元数据，须为未结束的已派发记录且保留原六十秒期限
     * @param input 平台白名单投影的专用输入，不能传递完整投影回执
     * @return 有界内部JSON字节；关联字段不属于供应商消息
     * @throws IllegalArgumentException 元数据、专用投影或报文字节限制不符合合同
     */
    public static byte[] encode(AnalysisCall call, Input input) {
        byte[] evidence = null;
        byte[] result = null;
        try {
            if (call == null || call.id() == null || call.id().version() != 7 || call.id().variant() != 2
                    || call.configurationRevision() < 1 || call.status() != AnalysisCall.Status.DISPATCHED
                    || call.createdAt() == null || call.deadline() == null || call.dispatchedAt() == null
                    || call.expiresAt() == null || call.finishedAt() != null
                    || !Duration.between(call.createdAt(), call.deadline()).equals(Duration.ofSeconds(60))
                    || call.dispatchedAt().isBefore(call.createdAt()) || !call.dispatchedAt().isBefore(call.deadline())
                    || !call.expiresAt().isAfter(call.deadline())
                    || input == null || !"device-1".equals(input.deviceAlias()) || input.readings().size() > 10) {
                throw new IllegalArgumentException();
            }
            long deadline = call.deadline().toEpochMilli();
            if (deadline < 1) throw new IllegalArgumentException();
            evidence = JSON.writeValueAsBytes(input);
            if (evidence.length > MAX_INPUT_BYTES) throw new IllegalArgumentException();
            var body = JSON.createObjectNode();
            body.put("version", "agent-internal-analysis-v1");
            body.put("callId", call.id().toString());
            body.put("configurationRevision", call.configurationRevision());
            body.put("deadlineEpochMillis", deadline);
            body.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence)));
            body.put("inputBase64", Base64.getEncoder().encodeToString(evidence));
            byte[] encoded = JSON.writeValueAsBytes(body);
            if (encoded.length > MAX_INTERNAL_BYTES) throw new IllegalArgumentException();
            result = encoded;
        } catch (Exception ignored) {
            // 统一拒绝，不把序列化异常、字段路径或输入值作为原因链传出。
        } finally {
            if (evidence != null) Arrays.fill(evidence, (byte) 0);
        }
        if (result == null) throw new IllegalArgumentException("INVALID_INTERNAL_ANALYSIS_REQUEST");
        return result;
    }
}
