package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0063 §5：先证明公开目录缺失受控路由，不以尚未存在的生产常量制造编译失败。 */
class ModbusNormalizedOutboxRouteTests {

    /** 已冻结的响应交付类型必须由同一个目录映射到标准上行主题。 */
    @Test
    void mapsFrozenNormalizedEventToItsControlledTopic() {
        assertThat(OutboxRouteCatalog.topicFor("DEVICE_MODBUS_NORMALIZED")).isEqualTo("tc.device.uplink.normalized");
    }

    /** 写入值对象必须接受新类型，才能在业务事务内留下持久交付记录。 */
    @Test
    void derivesDurableDestinationFromTheNormalizedEventType() {
        UUID subDeviceId = Uuid7.generate();
        OutboxEvent event = new OutboxEvent(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "DEVICE_MODBUS_RESULT", subDeviceId, "DEVICE_MODBUS_NORMALIZED", subDeviceId.toString(),
                "{}", "d122-route", Instant.now());
        assertThat(event.destinationTopic()).isEqualTo("tc.device.uplink.normalized");
    }

    /** 即使类型受支持，调用方也不能显式把 normalized 指向设备下行。 */
    @Test
    void rejectsInjectedDestinationForTheNormalizedEventType() {
        UUID subDeviceId = Uuid7.generate();
        assertThatThrownBy(() -> new OutboxEvent(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "DEVICE_MODBUS_RESULT", subDeviceId, "DEVICE_MODBUS_NORMALIZED", "tc.device.downlink",
                subDeviceId.toString(), "{}", "d122-route", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("事件类型与目标 Topic 不匹配");
    }
}
