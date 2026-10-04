package com.things.link.telemetry.application;

import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandClaimRepository;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 设备业务回复的统一入口：命令权威事实与终态语义完全复用既有状态机。
 *
 * <p>分类顺序是最小充分且不可交换的：先确认命令存在且属于该设备（未知或越权一律 {@code NOT_FOUND}，
 * 不写入任何事实），再交给既有 {@code DeviceCommandService.applyReply}（输出 Schema 校验、幂等 CAS、
 * 终态事件都在那里），最后只在状态机拒绝时区分「重放」与「同键异结果」。把顺序倒过来会让越权回复有机会
 * 留下尝试、诊断或事件，而合同 §5.4 要求越权回复不写入任何命令事实。</p>
 */
@Service
public class DeviceCommandAccessReplyService implements DeviceCommandAccessReplyPort {

    /** 已记录响应载荷的反序列化目标。 */
    private static final TypeReference<Map<String, Object>> OUTPUT_TYPE = new TypeReference<>() {
    };

    /** 命令事实仓储，用于确认命令存在且属于该设备。 */
    private final DeviceCommandRepository commandRepository;

    /** 投递事实仓储，用于读取该 messageId 已记录过的结果。 */
    private final DeviceCommandClaimRepository claimRepository;

    /** 既有命令状态机，含输出 Schema 校验与终态事件。 */
    private final DeviceCommandService commandService;

    /** 事务局部 RLS 范围组件。 */
    private final TransactionLocalRlsScope rlsScope;

    /** 保证范围设置、判定与状态推进在同一事务连接上。 */
    private final TransactionTemplate transactionTemplate;

    /** 统一 JSON 映射器，用于比较已记录输出与本次输出。 */
    private final ObjectMapper objectMapper;

    /**
     * @param commandRepository 命令事实仓储
     * @param claimRepository 投递事实仓储
     * @param commandService 既有命令状态机
     * @param rlsScope 事务局部 RLS 范围组件
     * @param transactionTemplate 事务模板
     * @param objectMapper 统一 JSON 映射器
     */
    public DeviceCommandAccessReplyService(DeviceCommandRepository commandRepository,
                                          DeviceCommandClaimRepository claimRepository,
                                          DeviceCommandService commandService,
                                          TransactionLocalRlsScope rlsScope,
                                          TransactionTemplate transactionTemplate,
                                          ObjectMapper objectMapper) {
        this.commandRepository = commandRepository;
        this.claimRepository = claimRepository;
        this.commandService = commandService;
        this.rlsScope = rlsScope;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Outcome apply(Reply reply) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            rlsScope.establish(reply.tenantId(), reply.projectId());
            Optional<DeviceCommand> command = commandRepository.findById(
                    reply.projectId(), reply.deviceId(), reply.commandId());
            if (command.isEmpty() || !command.orElseThrow().connectionDeviceId().equals(reply.deviceId())) {
                // 未知命令、属于其他设备或网关代报路径都不接受，且不留下任何事实。
                return Outcome.NOT_FOUND;
            }
            boolean applied = commandService.applyReply(new DeviceCommandReply(
                    reply.messageId(), reply.tenantId(), reply.projectId(), reply.deviceId(), reply.commandId(),
                    reply.occurredAt(), reply.receivedAt(),
                    DeviceCommandReply.Status.valueOf(reply.status().name()), outputJson(reply), reply.errorCode(),
                    reply.message(), reply.traceId()));
            if (applied) {
                return Outcome.APPLIED;
            }
            return claimRepository.findStoredReply(reply.projectId(), reply.commandId(), reply.messageId())
                    .map(stored -> sameResult(stored, reply) ? Outcome.DUPLICATE : Outcome.CONFLICT)
                    // 命令已终态而该 messageId 从未记录：迟到的首次结果已被更强者取代，按重放不改变事实。
                    .orElse(Outcome.DUPLICATE);
        }), "业务回复不能返回空事务结果");
    }

    /** 回复输出统一序列化为对象 JSON；缺省为空对象，与既有推送路径一致。 */
    private String outputJson(Reply reply) {
        if (reply.output() == null || reply.output().isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(reply.output());
    }

    /** 同键同结果才算重放：状态与输出（键序无关）都必须一致，失败码也参与比较。 */
    private boolean sameResult(DeviceCommandClaimRepository.StoredReply stored, Reply reply) {
        String expectedStatus = switch (reply.status()) {
            case ACK -> "ACKNOWLEDGED";
            case SUCCESS -> "SUCCEEDED";
            case FAILED -> "FAILED";
        };
        if (!expectedStatus.equals(stored.status())) {
            return false;
        }
        if (reply.status() == Status.FAILED
                && !Objects.equals(reply.errorCode(), stored.failureCode())) {
            return false;
        }
        return Objects.equals(reply.output() == null ? Map.of() : reply.output(), storedOutput(stored));
    }

    /** 已记录输出按 JSON 对象比较；无法解析时退化为不相等，避免把损坏事实当成重放。 */
    private Map<String, Object> storedOutput(DeviceCommandClaimRepository.StoredReply stored) {
        if (stored.responseJson() == null || stored.responseJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(stored.responseJson(), OUTPUT_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException exception) {
            return Map.of();
        }
    }
}
