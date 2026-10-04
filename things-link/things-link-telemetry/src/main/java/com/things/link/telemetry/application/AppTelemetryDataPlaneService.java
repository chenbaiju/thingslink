package com.things.link.telemetry.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * App 遥测数据面端口（S11-2b）：历史聚合曲线与命令下发/状态。
 *
 * <p><b>信任边界</b>：全部方法<b>不做成员校验</b>，调用方（enduser 的 {@code AppDeviceAccessService}）
 * 必须先经 {@code app_user_device} 校验绑定与控制关系角色，再进入本端口。App 请求没有控制台
 * {@code accountId}，无法走控制台端口的成员鉴权；隔离由 App 安全链写入的 {@code RlsScopeContext}
 * 驱动 RLS 兜底，仓储 SQL 仍显式带 {@code project_id = ?}。</p>
 *
 * <p>粒度/聚合以字符串入参，在本端口解析为 domain 枚举并回映成字符串 —— enduser 只消费本模块
 * application 包的 DTO，不 import domain 枚举（ArchUnit 规则 2/3）。</p>
 */
@Service
public class AppTelemetryDataPlaneService {

    /** 历史查询应用服务，复用其无成员校验的核心。 */
    private final PropertyHistoryService historyService;
    /** 命令应用服务，复用其无控制校验的核心。 */
    private final DeviceCommandService commandService;
    /** 命令输出载荷解析器。 */
    private final ObjectMapper objectMapper;

    /** @param historyService 历史查询服务 @param commandService 命令服务 @param objectMapper JSON 映射器 */
    public AppTelemetryDataPlaneService(PropertyHistoryService historyService,
                                        DeviceCommandService commandService,
                                        ObjectMapper objectMapper) {
        this.historyService = historyService;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询属性历史聚合曲线。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性键
     * @param from 起始时刻（含）
     * @param to 结束时刻（不含）
     * @param granularity 请求粒度（RAW/ONE_MINUTE/ONE_HOUR/ONE_DAY）
     * @param aggregation 聚合函数（AVG/MIN/MAX/SUM/COUNT）
     * @return 历史聚合投影
     */
    public AppPropertyHistory history(UUID projectId, UUID deviceId, String propertyKey,
                                      Instant from, Instant to, String granularity, String aggregation) {
        HistoryGranularity requested = parseEnum(HistoryGranularity.class, granularity, "granularity");
        HistoryAggregation agg = parseEnum(HistoryAggregation.class, aggregation, "aggregation");
        PropertyHistoryResult result = historyService.queryTrusted(
                projectId, deviceId, propertyKey, from, to, requested, agg);
        return new AppPropertyHistory(result.requestedGranularity().name(), result.actualGranularity().name(),
                result.aggregation().name(), result.points().stream()
                .map(point -> new AppHistoryPoint(point.ts(), point.value(), point.sampleCount())).toList());
    }

    /**
     * 版本化历史不丢弃来源模型，且在最后粒度溢出时按10001拒绝完整序列。
     * @param projectId 已认证且已确权项目
     * @param deviceId 已验证绑定及当前模型的设备
     * @param propertyKey 已由运行计划声明的属性
     * @param from 窗口起点，包含
     * @param to 窗口终点，不包含
     * @param granularity 请求粒度
     * @param aggregation 聚合操作
     * @return 保留每点精确模型版本的完整有界历史
     */
    public AppVersionedPropertyHistory historyVersioned(UUID projectId, UUID deviceId, String propertyKey,
            Instant from, Instant to, String granularity, String aggregation) {
        HistoryGranularity requested = parseEnum(HistoryGranularity.class, granularity, "granularity");
        HistoryAggregation agg = parseEnum(HistoryAggregation.class, aggregation, "aggregation");
        PropertyHistoryResult result = historyService.queryVersionedTrusted(
                projectId, deviceId, propertyKey, from, to, requested, agg);
        return new AppVersionedPropertyHistory(result.requestedGranularity().name(), result.actualGranularity().name(),
                result.aggregation().name(), result.points().stream().map(point -> new AppVersionedHistoryPoint(
                        point.ts(), point.value(), point.sampleCount(), point.thingModelVersionId(), point.modelVersion())).toList());
    }

    /**
     * 受理设备命令。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param idempotencyKey 业务幂等键
     * @param commandKey 物模型命令键
     * @param input 命令输入对象
     * @param appUserId 发起终端用户
     * @return 命令结果投影
     */
    public AppCommandResult submit(UUID projectId, UUID deviceId, String idempotencyKey,
                                   String commandKey, JsonNode input, UUID appUserId) {
        DeviceCommand command = commandService.submitTrusted(
                projectId, deviceId, idempotencyKey, commandKey, input, null, appUserId);
        return AppCommandResult.from(command, objectMapper);
    }

    /**
     * 回读命令状态。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param commandId 命令 ID
     * @return 命令结果投影；不存在（含跨项目）时为空
     */
    public Optional<AppCommandResult> status(UUID projectId, UUID deviceId, UUID commandId) {
        return commandService.findTrusted(projectId, deviceId, commandId)
                .map(details -> AppCommandResult.from(details.command(), objectMapper));
    }

    /** 把不可信字符串解析为枚举，未知取值按参数错误处理而非 500。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, field + " 取值不合法");
        }
    }
}
