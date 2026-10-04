package com.things.link.simulator.api.controller;

import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.simulator.application.DeviceSimulator;
import com.things.link.simulator.application.MqttDeviceClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分片规模的服务层校验：超单分片上限必须被拒绝，10,000 台只能靠多进程/多节点分片。 */
class SimulatorControllerTests {

    /** 校验发生在连接之前，工厂不会被调用，用抛异常的桩即可证明未越界进入建连。 */
    private final MqttDeviceClientFactory unusedFactory = (brokerUri, projectKey, deviceKey, accessToken) -> {
        throw new UnsupportedOperationException("分片校验应在建连前拦截");
    };

    /** 建连前 start() 仍会打开 manifest；用临时目录避免污染工作目录。 */
    @TempDir
    Path manifestDir;

    @Test
    void rejectsRequestExceedingShardLimit() {
        DeviceSimulator simulator = new DeviceSimulator(unusedFactory, new ObjectMapper(), manifestDir.toString());
        SimulatorController controller = new SimulatorController(simulator, 500);

        SimulationRequest request = new SimulationRequest(
                "tcp://localhost:1883", "project_1", devices(501), 60);

        assertThatThrownBy(() -> controller.start(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("单分片最多 500");
    }

    /** 恰好等于分片上限时不被服务层拒绝（HTTP 层 @Size(max=1000) 另行兜底）。 */
    @Test
    void acceptsRequestWithinShardLimit() {
        DeviceSimulator simulator = new DeviceSimulator(unusedFactory, new ObjectMapper(), manifestDir.toString());
        SimulatorController controller = new SimulatorController(simulator, 500);

        SimulationRequest request = new SimulationRequest(
                "tcp://localhost:1883", "project_1", devices(500), 60);

        // 校验通过后会进入建连，工厂桩抛 UnsupportedOperationException，证明没有被 400 拦截。
        assertThatThrownBy(() -> controller.start(request))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 软上限只能下调 HTTP 硬保护，0 或超过 1000 都必须在启动装配阶段 fail-fast。 */
    @Test
    void rejectsInvalidConfiguredShardLimit() {
        DeviceSimulator simulator = new DeviceSimulator(unusedFactory, new ObjectMapper(), manifestDir.toString());

        assertThatThrownBy(() -> new SimulatorController(simulator, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1..1000");
        assertThatThrownBy(() -> new SimulatorController(simulator, 1001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1..1000");
    }

    /** 构造指定数量的合法设备凭据。 */
    private static List<SimulationRequest.DeviceCredential> devices(int count) {
        List<SimulationRequest.DeviceCredential> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new SimulationRequest.DeviceCredential("device_" + i, "secret-" + i));
        }
        return result;
    }
}
