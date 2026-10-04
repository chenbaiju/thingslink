package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 五条App运行数据接口中三个POST查询的封闭请求类型集合。 */
public final class WebAppRuntimeDataRequest {
    /** 禁止实例化纯DTO命名空间。 */
    private WebAppRuntimeDataRequest() { }

    /**
     * 设备描述快照请求。
     * @param models 最多20个模型引用
     * @param devices 1至20个设备请求
     */
    @Schema(name = "WebAppDeviceSnapshotQueryRequest")
    public record Snapshot(
            @NotNull @ArraySchema(maxItems = 20, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Model> models,
            @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Device> devices) { }

    /**
     * 稀疏当前值请求。
     * @param devices 1至20个设备和键
     */
    @Schema(name = "WebAppCurrentValueQueryRequest")
    public record Current(
            @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<CurrentDevice> devices) { }

    /**
     * 告警实例查询请求。
     * @param devices 1至20个设备及模型
     * @param conditionStates 条件状态闭集
     * @param ackStates 确认状态闭集
     * @param severities 等级闭集
     * @param cursor 可选签名游标
     * @param limit 页大小；省略为20
     */
    @Schema(name = "WebAppAlarmQueryRequest")
    public record Alarms(
            @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<AlarmDevice> devices,
            @NotNull @ArraySchema(minItems = 1, maxItems = 3, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"PENDING", "ACTIVE", "CLEARED"}))
            Set<String> conditionStates,
            @NotNull @ArraySchema(minItems = 1, maxItems = 2, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"UNACKNOWLEDGED", "ACKNOWLEDGED"}))
            Set<String> ackStates,
            @NotNull @ArraySchema(minItems = 1, maxItems = 5, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"}))
            Set<String> severities,
            @Schema(maxLength = 2048) String cursor,
            @Schema(defaultValue = "20", minimum = "1", maximum = "50") Integer limit) { }

    /**
     * 请求模型完整身份。
     * @param versionId 模型版本
     * @param digestAlgorithm 摘要算法
     * @param digest 摘要
     * @param profile 复合属性Profile
     */
    @Schema(name = "WebAppRuntimeModelRequest")
    public record Model(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID versionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String digestAlgorithm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String digest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String profile) { }

    /**
     * 设备属性请求。
     * @param deviceId 设备ID
     * @param expectedModelVersionId 预期当前模型
     * @param propertyKeys 顶层属性键
     */
    @Schema(name = "WebAppRuntimeDeviceRequest")
    public record Device(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId,
            @NotNull @ArraySchema(minItems = 0, maxItems = 50, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<String> propertyKeys) { }

    /**
     * 当前值设备请求要求至少一个属性键。
     * @param deviceId 设备ID
     * @param expectedModelVersionId 预期当前模型
     * @param propertyKeys 1至50个顶层属性键
     */
    @Schema(name = "WebAppCurrentValueDeviceRequest")
    public record CurrentDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId,
            @NotNull @ArraySchema(minItems = 1, maxItems = 50, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<String> propertyKeys) { }

    /**
     * 告警设备选择，不携带任意属性键。
     * @param deviceId 设备ID
     * @param expectedModelVersionId 预期当前模型
     */
    @Schema(name = "WebAppAlarmDeviceRequest")
    public record AlarmDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId) { }
}
