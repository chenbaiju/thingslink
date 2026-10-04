package com.things.link.ingestion.application.access;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话内下行推送端口（接入合同 §5.3）。
 *
 * <p>推送形态与领取形态是同一份命令事实的两种投递方式：本端口只回答「这条命令有没有被写进某条活跃会话」，
 * 不推进命令状态、不伪造执行结果。**写成功不等于设备执行成功**——设备是否真的做了事仍由业务回复决定；
 * 写成功也因此只表示「可以记为已派发」，终态仍由既有命令状态机持有。</p>
 *
 * <p>端口只按设备找**本实例**的活跃会话：跨实例归属与路由属 AX-3d，本端口不做任何跨实例猜测。</p>
 */
public interface DeviceAccessPushPort {

    /** 一次推送的结果。 */
    enum PushOutcome {
        /** 帧已写入活跃会话（不代表设备已执行）。 */
        DELIVERED,
        /** 本实例没有该设备的活跃会话：命令必须保持待发，由重投节奏再试。 */
        DEFERRED_NOT_OWNER,
        /** 会话存在但写入失败（对端已断等）：同样不得记为已派发。 */
        WRITE_FAILED
    }

    /**
     * 一条待推送的下行命令。
     *
     * @param tenantId 命令归属租户，用于与本地会话归属交叉校验
     * @param projectId 命令归属项目，用于与本地会话归属交叉校验
     * @param deviceId 实际承载连接的设备（§5.5：直连设备下等于目标设备）
     * @param commandId 稳定命令 ID
     * @param commandKey 受理时冻结的命令标识符
     * @param inputJson 已通过输入 Schema 校验的命令参数 JSON 文本
     * @param attempt 本次投递序号，仅用于诊断，不是幂等键
     * @param expiresAt 本次响应截止时刻，与冻结合同的 {@code expiresAt} 对应
     */
    record Push(UUID tenantId, UUID projectId, UUID deviceId, UUID commandId, String commandKey,
                String inputJson, int attempt, Instant expiresAt) {

        /** 冻结推送的必填字段。 */
        public Push {
            if (tenantId == null || projectId == null || deviceId == null || commandId == null
                    || commandKey == null || commandKey.isBlank() || inputJson == null || attempt < 1) {
                throw new IllegalArgumentException("会话推送的归属、命令与投递序号不能为空");
            }
        }
    }

    /**
     * 把一条命令写进该设备在本实例的活跃会话。
     *
     * @param push 已确权的待推送命令
     * @return 写入结果；调用方必须按 {@link PushOutcome} 区分「已写入」与「未送达」
     */
    PushOutcome push(Push push);

    /** 写前准入筛选；不拥有会话时必须在规则链和状态推进之前跳过。 */
    boolean owns(Push push);
}
