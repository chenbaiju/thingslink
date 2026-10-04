package com.things.link.device.api.controller;

import com.things.link.device.api.dto.response.DeviceRuntimeCurrentValuesResponse;
import com.things.link.device.api.dto.response.DeviceRuntimeSnapshotResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.api.support.DeviceRuntimeQueryRequestParser;
import com.things.link.device.application.ConsoleDeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Console设备运行控制器的最终UTF-8响应预算边界测试。 */
class DeviceRuntimeSnapshotControllerTests {
    /** 服务端冻结的最终JSON响应上限。 */ private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 路径项目。 */ private final UUID project = UUID.randomUUID();
    /** 请求设备。 */ private final UUID device = UUID.randomUUID();
    /** 请求模型。 */ private final UUID model = UUID.randomUUID();
    /** 真实Jackson用于计算最终编码边界。 */ private final ObjectMapper mapper = new ObjectMapper();
    /** 设备运行编排替身。 */ private ConsoleDeviceRuntimeDataService service;
    /** HTTP读取授权替身。 */ private DeviceApiAuthorization authorization;
    /** 被测控制器。 */ private DeviceRuntimeSnapshotController controller;

    /** 严格解析使用真实实现，业务结果以替身精确制造HTTP包装边界。 */
    @BeforeEach
    void setUp() {
        service = mock(ConsoleDeviceRuntimeDataService.class);
        authorization = mock(DeviceApiAuthorization.class);
        controller = new DeviceRuntimeSnapshotController(service, authorization,
                new DeviceRuntimeQueryRequestParser(), mapper);
    }

    /** GET发现端点必须先执行Console读守卫，并返回禁止缓存的完整响应。 */
    @Test
    void bindingMetadataAuthorizesBeforeReadAndDisablesCaching() {
        when(service.bindingMetadata(project, device)).thenReturn(snapshot("泵站"));
        var response = controller.bindingMetadata(project, device, new MockHttpServletRequest());
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(mapper.readTree(response.getBody()).path("devices").get(0).path("name").asText()).isEqualTo("泵站");
        var order = org.mockito.Mockito.inOrder(authorization, service);
        order.verify(authorization).requireRead(project);
        order.verify(service).bindingMetadata(project, device);
    }

    /** 无权主体不读数据，额外query同样不能被忽略后执行。 */
    @Test
    void bindingMetadataRejectsPermissionAndUnknownQuery() {
        org.mockito.Mockito.doThrow(new IllegalStateException("不可见项目")).when(authorization).requireRead(project);
        assertThatThrownBy(() -> controller.bindingMetadata(project, device, new MockHttpServletRequest()))
                .hasMessageContaining("不可见项目");
        org.mockito.Mockito.verifyNoInteractions(service);
        org.mockito.Mockito.reset(authorization);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString("include=secret");
        assertThatThrownBy(() -> controller.bindingMetadata(project, device, request))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    /** 新GET仍按最终编码字节限制，不因复用DTO而绕过4MiB响应保护。 */
    @Test
    void bindingMetadataEnforcesFinalResponseBudget() {
        int fixed = mapper.writeValueAsBytes(DeviceRuntimeSnapshotResponse.from(snapshot(""))).length;
        when(service.bindingMetadata(project, device)).thenReturn(snapshot("a".repeat(MAX_BYTES - fixed)),
                snapshot("a".repeat(MAX_BYTES - fixed + 1)));
        assertThat(controller.bindingMetadata(project, device, new MockHttpServletRequest()).getBody()).hasSize(MAX_BYTES);
        assertThatThrownBy(() -> controller.bindingMetadata(project, device, new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("4MiB");
    }

    /** 设备快照最终JSON恰等于4MiB可发送，多一字节必须内部失败且不能截断名称。 */
    @Test
    void enforcesSnapshotFinalFourMebibyteBoundary() {
        int fixed = mapper.writeValueAsBytes(DeviceRuntimeSnapshotResponse.from(snapshot(""))).length;
        when(service.querySnapshots(any(), anyList(), anyList()))
                .thenReturn(snapshot("a".repeat(MAX_BYTES - fixed)),
                        snapshot("a".repeat(MAX_BYTES - fixed + 1)));

        assertThat(controller.snapshots(project, snapshotBody(), request("snapshots")).getBody()).hasSize(MAX_BYTES);
        assertThatThrownBy(() -> controller.snapshots(project, snapshotBody(), request("snapshots")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("4MiB");
    }

    /** 稀疏当前值最终JSON恰等于4MiB可发送，多一字节必须内部失败且不能裁掉已证明值。 */
    @Test
    void enforcesCurrentValueFinalFourMebibyteBoundary() {
        int fixed = mapper.writeValueAsBytes(DeviceRuntimeCurrentValuesResponse.from(current(""))).length;
        when(service.queryCurrentValues(any(), anyList()))
                .thenReturn(current("a".repeat(MAX_BYTES - fixed)),
                        current("a".repeat(MAX_BYTES - fixed + 1)));

        assertThat(controller.currentValues(project, currentBody(), request("current-value-snapshots"))
                .getBody()).hasSize(MAX_BYTES);
        assertThatThrownBy(() -> controller.currentValues(
                project, currentBody(), request("current-value-snapshots")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("4MiB");
    }

    /** 构造可用设备响应；超长名称仅是HTTP边界替身，不声称可通过持久列长度限制。 */
    private RuntimeDeviceSnapshotResult snapshot(String name) {
        return new RuntimeDeviceSnapshotResult(List.of(new RuntimeDeviceAvailability(device,
                RuntimeDeviceAvailability.Status.AVAILABLE, model, name, "ONLINE", null)), List.of());
    }

    /** 构造VALUE响应；超长TEXT仅是HTTP边界替身，不声称可通过物模型或PG写入限制。 */
    private RuntimeDeviceCurrentResult current(String value) {
        return new RuntimeDeviceCurrentResult(List.of(new RuntimeDeviceCurrentResult.DeviceValues(device,
                RuntimeDeviceAvailability.Status.AVAILABLE, List.of(new RuntimeDeviceCurrentResult.PropertyValue(
                "value", RuntimeDeviceCurrentResult.State.VALUE, JsonNodeFactory.instance.stringNode(value),
                Instant.parse("2026-09-07T01:02:03Z"), model)))));
    }

    /** 快照严格信封包含请求模型和空键设备。 */
    private byte[] snapshotBody() {
        return ("{\"models\":[{\"versionId\":\"" + model + "\",\"digestAlgorithm\":"
                + "\"PG_JSONB_TEXT_V1_SHA256\",\"digest\":\"" + "a".repeat(64)
                + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}],\"devices\":[{\"deviceId\":\""
                + device + "\",\"expectedModelVersionId\":\"" + model + "\",\"propertyKeys\":[]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 当前值严格信封至少请求一个属性。 */
    private byte[] currentBody() {
        return ("{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\""
                + model + "\",\"propertyKeys\":[\"value\"]}]}").getBytes(StandardCharsets.UTF_8);
    }

    /** 无额外query的规范Console只读POST。 */
    private MockHttpServletRequest request(String resource) {
        return new MockHttpServletRequest("POST", "/api/v1/projects/" + project + "/devices/" + resource + "/query");
    }
}
