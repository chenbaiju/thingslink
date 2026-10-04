package com.things.link.ingestion.application;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 下行预处理链测试，固定稳定顺序、空链直通和路由字段不可变约束。 */
class DownlinkPreprocessingChainTests {

    /** 两个处理器必须按 order 顺序变换 input，不能依赖 Bean 扫描偶然顺序。 */
    @Test
    void appliesProcessorsInStableOrder() {
        DeviceCommandDispatch source = dispatch("{}");
        List<String> calls = new ArrayList<>();
        DownlinkPreprocessor second = processor(20, "second", calls);
        DownlinkPreprocessor first = processor(10, "first", calls);

        DeviceCommandDispatch result = new DownlinkPreprocessingChain(List.of(second, first)).apply(source);

        assertThat(calls).containsExactly("first", "second");
        assertThat(result.inputJson()).isEqualTo("{\"step\":\"second\"}");
        assertThat(result.commandId()).isEqualTo(source.commandId());
    }

    /** 空处理器列表是 S4-3 默认形态，必须返回原对象且不制造新信封。 */
    @Test
    void emptyChainPassesThrough() {
        DeviceCommandDispatch source = dispatch("{}");

        assertThat(new DownlinkPreprocessingChain(List.of()).apply(source)).isSameAs(source);
    }

    /** 租户规则不得把实际连接设备改为另一设备，否则会形成跨设备命令下发。 */
    @Test
    void rejectsProcessorThatChangesConnectionDevice() {
        DeviceCommandDispatch source = dispatch("{}");
        DownlinkPreprocessor malicious = value -> copy(value, value.inputJson(), UUID.randomUUID());

        assertThatThrownBy(() -> new DownlinkPreprocessingChain(List.of(malicious)).apply(source))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasMessageContaining("不得修改");
    }

    /** 创建仅替换 input 的有序测试处理器。 */
    private static DownlinkPreprocessor processor(int order, String marker, List<String> calls) {
        return new DownlinkPreprocessor() {
            /** {@inheritDoc} */
            @Override public DeviceCommandDispatch preprocess(DeviceCommandDispatch value) {
                calls.add(marker);
                return copy(value, "{\"step\":\"" + marker + "\"}", value.connectionDeviceId());
            }

            /** {@inheritDoc} */
            @Override public int order() {
                return order;
            }
        };
    }

    /** 复制信封时只开放测试关心的 input 与 connectionDeviceId 两个变量。 */
    private static DeviceCommandDispatch copy(DeviceCommandDispatch value, String input, UUID connectionId) {
        return new DeviceCommandDispatch(value.eventId(), value.tenantId(), value.projectId(), value.commandId(),
                value.attemptId(), value.attemptNo(), value.targetDeviceId(), value.targetDeviceKey(), connectionId,
                value.connectionDeviceKey(), value.projectKey(), value.commandKey(), input, value.deadlineAt(),
                value.traceId());
    }

    /** 创建一条完整直连命令派发信封。 */
    private static DeviceCommandDispatch dispatch(String input) {
        UUID deviceId = Uuid7.generate();
        return new DeviceCommandDispatch(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), 1, deviceId, "device_1", deviceId, "device_1", "project_1", "reboot", input,
                Instant.parse("2026-08-08T03:00:00Z"), "0123456789abcdef0123456789abcdef");
    }
}
