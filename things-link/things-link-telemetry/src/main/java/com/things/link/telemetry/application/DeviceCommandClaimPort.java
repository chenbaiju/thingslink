package com.things.link.telemetry.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 协议无关的命令领取端口（接入合同 §5.2）。
 *
 * <p>领取不是第二套命令语义：它推进的是同一份命令事实与同一套终态；本端口只负责「按设备领取一批到期命令并
 * 落租约」。协议层（HTTP／CoAP）负责把返回值编码成各自的响应，不得自行解释或改写命令状态。</p>
 */
public interface DeviceCommandClaimPort {

    /** 领取租约默认时长；与接入合同 §6 冻结的 30 秒一致。 */
    Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    /** 租约允许的最小值；更短的租约会让慢设备反复重领同一命令。 */
    Duration MIN_LEASE = Duration.ofSeconds(10);

    /** 租约允许的最大值；更长的租约会把命令长期锁在一个领取者手里。 */
    Duration MAX_LEASE = Duration.ofSeconds(300);

    /** 单次领取默认条数；与接入合同 §5.2 冻结的「默认 1」一致。 */
    int DEFAULT_LIMIT = 1;

    /** 单次领取条数上限；与接入合同 §5.2 冻结的「上限 10」一致。 */
    int MAX_LIMIT = 10;

    /**
     * 一次领取请求。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 认证时权威项目，同时是 RLS 范围
     * @param deviceId 认证时权威设备，必须等于命令目标设备
     * @param lease 请求的租约时长；为空时取 {@link #DEFAULT_LEASE}，越界时收敛到上下限
     * @param limit 请求的条数；小于 1 时取 {@link #DEFAULT_LIMIT}，超过 {@link #MAX_LIMIT} 时收敛
     */
    record ClaimRequest(UUID tenantId, UUID projectId, UUID deviceId, Duration lease, int limit) {

        /** 冻结领取请求的必填归属。 */
        public ClaimRequest {
            if (tenantId == null || projectId == null || deviceId == null) {
                throw new IllegalArgumentException("领取归属不能为空");
            }
        }
    }

    /**
     * 领取到的命令。
     *
     * @param commandId 稳定命令 ID
     * @param commandKey 受理时冻结的命令标识符
     * @param input 已通过输入 Schema 校验的命令参数对象
     * @param attempt 本次投递序号，仅用于诊断，不是幂等键
     * @param leaseExpiresAt 租约到期时刻
     */
    record Claimed(UUID commandId, String commandKey, Map<String, Object> input, int attempt,
                   Instant leaseExpiresAt) {
    }

    /**
     * 领取一批到期命令。
     *
     * @param request 已认证的领取请求
     * @return 本次领取到的命令；没有可领取命令时返回空列表，绝不返回空元素
     */
    List<Claimed> claim(ClaimRequest request);
}
