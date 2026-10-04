package com.things.link.device.api.dto.response;

import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 设备运行HTTP投影的状态闭集、nullable字段和未经证明字段省略测试。 */
class DeviceRuntimeResponseTests {
    /** 使用与生产同代Jackson验证最终JSON字段，而非仅检查record getter。 */
    private final JsonMapper json = JsonMapper.builder().build();

    /** 三态设备使用不同闭集，模型失配的未知当前模型仍必须显式返回JSON null。 */
    @Test
    void snapshotDeviceBranchesPreserveClosedFieldsAndRequiredNullableModel() {
        UUID hidden = UUID.randomUUID();
        UUID mismatch = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        var result = new RuntimeDeviceSnapshotResult(List.of(
                new RuntimeDeviceAvailability(hidden, RuntimeDeviceAvailability.Status.NOT_AVAILABLE,
                        null, null, null, null),
                new RuntimeDeviceAvailability(mismatch, RuntimeDeviceAvailability.Status.MODEL_MISMATCH,
                        null, null, null, null),
                new RuntimeDeviceAvailability(available, RuntimeDeviceAvailability.Status.AVAILABLE,
                        model, "温度设备", "ONLINE", Instant.parse("2026-09-07T00:00:00Z"))), List.of());

        JsonNode devices = json.valueToTree(DeviceRuntimeSnapshotResponse.from(result)).path("devices");

        assertThat(devices.get(0).propertyNames()).containsExactlyInAnyOrder("deviceId", "status");
        assertThat(devices.get(1).propertyNames())
                .containsExactlyInAnyOrder("deviceId", "status", "currentModelVersionId");
        assertThat(devices.get(1).path("currentModelVersionId").isNull()).isTrue();
        assertThat(devices.get(2).propertyNames()).containsExactlyInAnyOrder(
                "deviceId", "status", "name", "deviceStatus", "lastOnlineAt", "currentModelVersionId");
    }

    /** 非VALUE项只返回属性键与状态，不能泄露来源模型、旧值或旧时间。 */
    @Test
    void sparseNonValueBranchesOmitUnprovenFacts() {
        UUID device = UUID.randomUUID();
        var result = new RuntimeDeviceCurrentResult(List.of(new RuntimeDeviceCurrentResult.DeviceValues(
                device, RuntimeDeviceAvailability.Status.AVAILABLE, List.of(
                new RuntimeDeviceCurrentResult.PropertyValue("temperature",
                        RuntimeDeviceCurrentResult.State.NO_VALUE, null, null, null)))));

        JsonNode value = json.valueToTree(DeviceRuntimeCurrentValuesResponse.from(result))
                .path("devices").get(0).path("values").get(0);

        assertThat(value.propertyNames()).containsExactlyInAnyOrder("propertyKey", "state");
    }

    /** VALUE分支固定返回同次PG行证明的值、时刻与来源版本，机器合同不把它们误标为可选。 */
    @Test
    void sparseValueBranchReturnsAllProvenFacts() {
        UUID device = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-07T01:02:03Z");
        var result = new RuntimeDeviceCurrentResult(List.of(new RuntimeDeviceCurrentResult.DeviceValues(
                device, RuntimeDeviceAvailability.Status.AVAILABLE, List.of(
                new RuntimeDeviceCurrentResult.PropertyValue("temperature", RuntimeDeviceCurrentResult.State.VALUE,
                        json.readTree("21.5"), occurredAt, model)))));

        JsonNode value = json.valueToTree(DeviceRuntimeCurrentValuesResponse.from(result))
                .path("devices").get(0).path("values").get(0);

        assertThat(value.propertyNames()).containsExactlyInAnyOrder(
                "propertyKey", "state", "value", "occurredAt", "reportedModelVersionId");
        assertThat(value.path("reportedModelVersionId").asString()).isEqualTo(model.toString());
    }
}
