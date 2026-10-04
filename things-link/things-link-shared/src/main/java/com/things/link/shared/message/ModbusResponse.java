package com.things.link.shared.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 网关对 Modbus 读请求的响应（{@code up/modbus/response}）。
 *
 * <p>{@code data} 为原始寄存器/位单元值数组，由 device 模块按点位的数据类型/字节序组装成属性值并
 * 经 normalized 进入 telemetry。{@code status} 为 {@code SUCCESS}/{@code ERROR}，失败时携带 errorCode。</p>
 *
 * @param requestId 原始读请求的关联标识
 * @param tenantId 网关所属租户（接入确权派生）
 * @param projectId 网关所属项目（接入确权派生）
 * @param gatewayId 已认证的网关设备 ID
 * @param status 响应结果
 * @param data 原始寄存器/位单元值
 * @param errorCode 失败原因稳定码，仅 ERROR 时非空
 * @param receivedAt 平台接收时刻
 * @param traceId 链路追踪标识
 */
public record ModbusResponse(UUID requestId, UUID tenantId, UUID projectId, UUID gatewayId,
                             Status status, List<Integer> data, String errorCode,
                             Instant receivedAt, String traceId) {

    /** 响应结果。 */
    public enum Status { /** 读取成功。 */ SUCCESS, /** 读取失败（超时/校验/从站错误）。 */ ERROR }

    /**
     * 冻结响应信封的必填字段与不可变性。
     */
    public ModbusResponse {
        if (requestId == null || requestId.version() != 7) {
            throw new IllegalArgumentException("requestId 必须是 UUIDv7");
        }
        if (tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("Modbus 响应归属不能为空");
        }
        if (status == null || receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("Modbus 响应状态与时间不完整");
        }
        data = data == null ? List.of() : List.copyOf(data);
        if (status == Status.ERROR && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("失败响应必须携带 errorCode");
        }
        if (status == Status.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException("成功响应不得携带 errorCode");
        }
    }
}
