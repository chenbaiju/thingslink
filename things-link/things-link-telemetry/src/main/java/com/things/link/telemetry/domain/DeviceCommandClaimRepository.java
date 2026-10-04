package com.things.link.telemetry.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备主动领取命令的投递事实仓储端口。
 *
 * <p>调用方必须已经在事务内建立完整的项目 RLS 范围；仓储不做身份解析，也不提供跨项目入口。领取是**一次投递**：
 * 命中候选命令后在同一事务内推进命令状态（DISPATCHED、attempt+1、租约写入 next_attempt_at 与 deadline_at）
 * 并写入领取行，两件事同生共死，不能只写其一。</p>
 */
public interface DeviceCommandClaimRepository {

    /**
     * 为指定设备领取到期且可投递的命令。
     *
     * <p>候选判据：命令属于该设备且尚未终态、已到可投递时刻、尝试次数未耗尽；当已有投递序号时，该序号的
     * 投递事实必须是领取行——这样已经推送过的命令不会被领取端点抢走，也无需给命令表新增投递形态列。</p>
     *
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 已认证设备 ID，必须等于命令目标设备
     * @param lease 本次租约时长
     * @param limit 单次最多领取条数
     * @return 本次领取到的命令视图，按受理先后排序
     */
    List<ClaimedCommand> claimDue(UUID projectId, UUID deviceId, Duration lease, int limit);

    /**
     * 统计该设备尚未终态的命令数，供待发队列预算打点。
     *
     * <p>只为度量存在：超出预算**不丢弃**命令（合同 §5.2），命令继续待发直到被领取或到期进入终态。</p>
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 未终态命令数
     */
    long countPending(UUID projectId, UUID deviceId);

    /**
     * 读取该回复消息标识已经记录过的结果。
     *
     * <p>结果取自命令事实本身：回复要么改变命令状态与响应载荷，要么被命令状态机拒绝；因此「这一 messageId
     * 是否已记录」决定重放与冲突，而命令当前回复结果就是首次结果。查询同时覆盖推送与领取两种投递形态——
     * 回复去重是投递事实的属性，不因形态不同而改变。</p>
     *
     * @param projectId 项目 ID
     * @param commandId 命令 ID
     * @param replyMessageId 业务回复消息标识
     * @return 已记录过该回复时的首次结果；该消息标识从未记录时为空
     */
    Optional<StoredReply> findStoredReply(UUID projectId, UUID commandId, UUID replyMessageId);

    /**
     * 已记录的业务回复结果。
     *
     * @param status 命令状态（ACKNOWLEDGED／SUCCEEDED／FAILED）
     * @param responseJson 已记录的响应载荷 JSON
     * @param failureCode 已记录的失败诊断码
     */
    record StoredReply(String status, String responseJson, String failureCode) {
    }

    /**
     * 一次领取返回给设备的命令视图。
     *
     * @param commandId 稳定命令 ID
     * @param commandKey 受理时冻结的命令标识符
     * @param input 已通过输入 Schema 校验的命令参数对象
     * @param attempt 本次投递序号，仅用于诊断，不是幂等键
     * @param leaseExpiresAt 租约到期时刻
     */
    record ClaimedCommand(UUID commandId, String commandKey, Map<String, Object> input, int attempt,
                          Instant leaseExpiresAt) {
    }
}
