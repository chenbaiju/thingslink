package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定编码与下行目标边界；不以纯函数测试替代真实Broker会话隔离。 */
class DeviceMqttSessionIdentityTests {
    /** 独立Python计算的黄金值，包含原Client ID空格、换行及非ASCII字节。 */
    @Test void matchesFrozenUtf8EncodingWithoutTrimming() {
        var auth = identity(1,2,3,7);
        var session = new DeviceMqttSessionIdentity(auth," client\n中文 ");
        assertThat(session.effectiveClientId()).isEqualTo("tc-device-d7edd483e2927ac881185debcee2b4bd2d28e182e1517a0304865fa3a7574a7f").hasSize(74);
        assertThat(session.attributes()).containsEntry("tc_auth_wire_client_id"," client\n中文 ")
                .containsEntry("tc_auth_mountpoint","tc/private/device/00000000-0000-0000-0000-000000000003/7/");
        assertThat(DeviceMqttIdentity.parse(session.attributes())).contains(auth);
        assertThatThrownBy(()->session.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 相同配置恢复相同会话，不同设备/归属/配置/原ID不会混用持久会话。 */
    @Test void isolatesEachFrozenIdentityComponent() {
        String baseline = new DeviceMqttSessionIdentity(identity(1,2,3,0),"client").effectiveClientId();
        assertThat(new DeviceMqttSessionIdentity(identity(1,2,3,0),"client").effectiveClientId()).isEqualTo(baseline);
        for (var auth : new DeviceMqttIdentity[]{identity(4,2,3,0),identity(1,4,3,0),identity(1,2,4,0),identity(1,2,3,1)}) {
            assertThat(new DeviceMqttSessionIdentity(auth,"client").effectiveClientId()).isNotEqualTo(baseline);
        }
        assertThat(new DeviceMqttSessionIdentity(identity(1,2,3,0)," client").effectiveClientId()).isNotEqualTo(baseline);
    }

    /** 字节长度而非Java字符数；有效空格不能trim，非法UTF-8与NUL必须拒绝。 */
    @Test void enforcesClientIdUtf8Boundary() {
        assertThat(DeviceMqttSessionIdentity.validClientId(" ")).isTrue();
        assertThat(DeviceMqttSessionIdentity.validClientId("界".repeat(21845))).isTrue();
        for (String value : new String[]{null,"","a\0b","\ud800","界".repeat(21846),"a".repeat(65536)}) {
            assertThat(DeviceMqttSessionIdentity.validClientId(value)).isFalse();
        }
    }

    /** 下行只包装原许可设备路径，不允许另一设备、上行、通配符或裸前缀。 */
    @Test void wrapsOnlyItsOwnDownlinkTopic() {
        var route = new DeviceMqttDownlinkRoute(new UUID(0,1),new UUID(0,2),new UUID(0,3),7,"project","device");
        assertThat(route.internalTopic("tc/v1/project/device/down/property/set"))
                .isEqualTo("tc/private/device/00000000-0000-0000-0000-000000000003/7/tc/v1/project/device/down/property/set");
        for (String topic : new String[]{null,"tc/v1/project/other/down/config","tc/v1/project/device/up/property",
                "tc/v1/project/device/down/","tc/v1/project/device/down/#","tc/v1/project/device/down/+","tc/v1/project/device/down/a\0"}) {
            assertThatThrownBy(()->route.internalTopic(topic)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 固定身份字段，凭据版本不替代配置代次。 */
    private static DeviceMqttIdentity identity(long tenant,long project,long device,long version) {
        return new DeviceMqttIdentity(new AuthenticatedDeviceIdentity(new UUID(0,tenant),new UUID(0,project),new UUID(0,device),1),version);
    }
}
