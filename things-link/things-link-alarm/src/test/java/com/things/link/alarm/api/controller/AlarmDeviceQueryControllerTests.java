package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.response.AlarmDeviceQueryResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.api.support.AlarmDeviceQueryRequestParser;
import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.alarm.application.ConsoleAlarmDeviceQueryService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Console只读告警控制器的显式响应闭集、授权顺序与最终字节守卫。 */
class AlarmDeviceQueryControllerTests {
    /** 真实编码器验证最终HTTP字节，不用估算字符串长度冒充。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 路径项目。 */
    private final UUID project = UUID.randomUUID();
    /** 请求设备。 */
    private final UUID device = UUID.randomUUID();
    /** 请求当前模型。 */
    private final UUID model = UUID.randomUUID();
    /** HTTP首层授权。 */
    private AlarmApiAuthorization authorization;
    /** 应用编排替身。 */
    private ConsoleAlarmDeviceQueryService service;
    /** 被测控制器。 */
    private AlarmDeviceQueryController controller;

    /** 原始请求解析器为真实实现，其余业务由独立用例覆盖。 */
    @BeforeEach
    void setup() {
        authorization = mock(AlarmApiAuthorization.class);
        service = mock(ConsoleAlarmDeviceQueryService.class);
        controller = new AlarmDeviceQueryController(authorization, new AlarmDeviceQueryRequestParser(), service, mapper);
    }

    /** item只有十二字段，三个nullable时刻保留且nextCursor末页明确null。 */
    @Test
    void returnsExactMinimalAlarmFacts() {
        when(service.query(any(), any(), any(), any(), any(), any(), any())).thenReturn(page("中文告警"));
        var response = controller.query(project, body(), request());
        var root = mapper.readTree(response.getBody());
        assertThat(root.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        var item = root.path("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "deviceId", "alarmType", "severity", "conditionState",
                "ackState", "firstConditionAt", "activatedAt", "clearedAt", "acknowledgedAt", "lastReceivedAt", "version");
        assertThat(item.path("activatedAt").isNull()).isTrue();
        assertThat(item.path("clearedAt").isNull()).isTrue();
        assertThat(item.path("acknowledgedAt").isNull()).isTrue();
        assertThat(root.path("nextCursor").isNull()).isTrue();
        assertThat(item.path("firstConditionAt").asString()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("中文告警");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    /** HTTP授权在原始JSON解析前拒绝，不让未授权请求探测内部结构。 */
    @Test
    void checksProjectReadBeforeParsing() {
        var failure = new IllegalStateException("项目授权失败");
        doThrow(failure).when(authorization).requireRead(project);
        assertThatThrownBy(() -> controller.query(project, "invalid-json".getBytes(StandardCharsets.UTF_8), request())).isSameAs(failure);
        verifyNoInteractions(service);
    }

    /** query不得成为原始JSON之外的第二组过滤输入。 */
    @Test
    void rejectsAdditionalQueryBeforeBusiness() {
        MockHttpServletRequest request = request();
        request.setQueryString("limit=1");
        assertThatThrownBy(() -> controller.query(project, body(), request)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
        verifyNoInteractions(service);
    }

    /** 只验证HTTP守卫的超大类型替身：4MiB同字节数组可发，多1字节必须内部失败，不能裁剪告警列表。 */
    @Test
    void enforcesActualFinalFourMebibyteBoundary() {
        int fixed = mapper.writeValueAsBytes(AlarmDeviceQueryResponse.from(page(""))).length;
        when(service.query(any(), any(), any(), any(), any(), any(), any())).thenReturn(page("a".repeat(4 * 1024 * 1024 - fixed)));
        assertThat(controller.query(project, body(), request()).getBody()).hasSize(4 * 1024 * 1024);
        when(service.query(any(), any(), any(), any(), any(), any(), any())).thenReturn(page("a".repeat(4 * 1024 * 1024 - fixed + 1)));
        assertThatThrownBy(() -> controller.query(project, body(), request())).isInstanceOf(IllegalStateException.class).hasMessageContaining("4MiB");
    }

    /** 空正文外没有额外query的只读POST。 */
    private MockHttpServletRequest request() { return new MockHttpServletRequest("POST", "/api/v1/projects/" + project + "/alarms/query"); }
    /** 合法封闭JSON，不依赖对象绑定的宽松标量转换。 */
    private byte[] body() {
        return ("{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\"" + model
                + "\"}],\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"MAJOR\"]}")
                .getBytes(StandardCharsets.UTF_8);
    }
    /** 包装边界的应用替身，超大alarmType不声称通过生产持久列约束。 */
    private CursorPage<AlarmDeviceQueryItem> page(String type) {
        Instant time = Instant.parse("2026-09-01T00:00:00Z");
        return CursorPage.last(List.of(new AlarmDeviceQueryItem(UUID.randomUUID(), device, type, "MAJOR", "ACTIVE",
                "UNACKNOWLEDGED", time, null, null, null, time, 1)));
    }
}
