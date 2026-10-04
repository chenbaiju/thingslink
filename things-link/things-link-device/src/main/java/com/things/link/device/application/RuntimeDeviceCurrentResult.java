package com.things.link.device.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 稀疏PG当前值的一次有界读取结果。
 *
 * @param devices 按请求设备顺序返回的状态和值
 */
public record RuntimeDeviceCurrentResult(List<DeviceValues> devices) {

    /** 冻结设备结果集合。 */
    public RuntimeDeviceCurrentResult {
        devices = List.copyOf(Objects.requireNonNull(devices, "devices"));
    }

    /**
     * 一台请求设备的当前值投影。
     *
     * @param deviceId 请求设备ID
     * @param status 设备可用性
     * @param values 可用时每个请求键恰一项；失败时为空
     */
    public record DeviceValues(
            UUID deviceId,
            RuntimeDeviceAvailability.Status status,
            List<PropertyValue> values) {

        /** 状态失败时禁止夹带旧值，可用时保持有序完整集合。 */
        public DeviceValues {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(status, "status");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (status != RuntimeDeviceAvailability.Status.AVAILABLE && !values.isEmpty()) {
                throw new IllegalArgumentException("不可用设备不得返回当前值");
            }
        }
    }

    /**
     * 一个顶层属性的当前值状态。
     *
     * @param propertyKey 请求属性键
     * @param state 当前值解释状态
     * @param value 仅VALUE携带的防御副本
     * @param occurredAt 仅VALUE携带的采集时刻
     * @param reportedModelVersionId 仅VALUE携带的真实来源版本
     */
    public record PropertyValue(
            String propertyKey,
            State state,
            JsonNode value,
            Instant occurredAt,
            UUID reportedModelVersionId) {

        /** VALUE字段必须齐全，其他状态不得泄露未经证明的业务值。 */
        public PropertyValue {
            Objects.requireNonNull(propertyKey, "propertyKey");
            Objects.requireNonNull(state, "state");
            value = value == null ? null : value.deepCopy();
            boolean complete = value != null && occurredAt != null && reportedModelVersionId != null;
            if (state == State.VALUE ? !complete
                    : value != null || occurredAt != null || reportedModelVersionId != null) {
                throw new IllegalArgumentException("当前值状态与事实字段不一致");
            }
        }

        /** @return 与内部事实隔离的JSON值 */
        @Override
        public JsonNode value() {
            return value == null ? null : value.deepCopy();
        }
    }

    /** 一个属性组合的确定解释状态。 */
    public enum State {
        /** 值、时间、来源和类型均有效。 */ VALUE,
        /** PG不存在该属性键。 */ NO_VALUE,
        /** 有值但没有来源版本。 */ SOURCE_VERSION_UNKNOWN,
        /** 来源版本不是请求模型。 */ SOURCE_MODEL_MISMATCH,
        /** 时间、类型、枚举或复合Profile不满足合同。 */ CONTRACT_MISMATCH
    }
}
