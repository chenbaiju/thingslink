package com.things.link.dashboard.api.controller;

import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.dashboard.api.dto.response.DashboardShareDataResponse;
import com.things.link.dashboard.api.support.DashboardShareDataRequestParser;
import com.things.link.dashboard.application.DashboardShareAlarmPage;
import com.things.link.dashboard.application.DashboardShareDataService;
import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.shared.error.BusinessException;
import com.things.link.telemetry.application.AppVersionedPropertyHistory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 控制器最低层只验证HTTP词法/编码/异常；真实匿名身份与RLS由Bootstrap全链测试覆盖。 */
class DashboardShareDataControllerTests {
    /** 公开能力选择器，测试不把它当真实凭据授权事实。 */
    private static final UUID SHARE = UUID.randomUUID();
    /** 有效输入中的设备身份。 */
    private static final UUID DEVICE = UUID.randomUUID();
    /** 有效输入中的模型身份。 */
    private static final UUID MODEL = UUID.randomUUID();
    /** 同编码器用于比较最终UTF-8而非Java字符长度。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 仅模拟应用返回数据以构造持久列无法提供的极限长度，不mock真实集成安全链。 */
    private final DashboardShareDataService service = mock(DashboardShareDataService.class);
    /** 被测HTTP边界。 */
    private final DashboardShareDataController controller = new DashboardShareDataController(new DashboardShareDataRequestParser(), service, mapper);

    /** exactly4MiB可交给过滤器预算，超过一个UTF-8字节必须503且保留首因，不截断成功。 */
    @Test void responseUsesExactUtf8FourMebibyteBound() throws Exception {
        int fixed = mapper.writeValueAsBytes(DashboardShareDataResponse.AlarmPage.from(page(""))).length;
        String content = "中" + "a".repeat(4 * 1024 * 1024 - fixed - 3);
        when(service.alarms(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(page(content));
        var request = request("POST");
        request.setContent(alarmBody());
        assertThat(controller.alarms(SHARE.toString(), request).getBody()).hasSize(4 * 1024 * 1024);
        when(service.alarms(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(page(content + "a"));
        var oversized = request("POST");
        oversized.setContent(alarmBody());
        assertCode(() -> controller.alarms(SHARE.toString(), oversized), 60055);
    }

    /** 读取limit+1即拒绝超过64KiB的正文，不先让Jackson构造无界树再检查业务字段。 */
    @Test void rejectsOversizedRawInputBeforeApplicationCall() {
        var request = request("POST");
        request.setContent(new byte[DashboardShareDataRequestParser.MAX_REQUEST_BYTES + 1]);
        assertCode(() -> controller.currentValues(SHARE.toString(), request), 10001);
        verifyNoInteractions(service);
    }

    /** 来源只能是内部请求属性；缺失或路径错配不从Cookie/session补造身份。 */
    @Test void rejectsAbsentOrMismatchedSharePrincipal() {
        var request = request("GET");
        request.removeAttribute(DashboardSharePrincipal.class.getName());
        assertCode(() -> controller.catalog(SHARE.toString(), request), 60053);
        assertCode(() -> controller.catalog(UUID.randomUUID().toString(), request("GET")), 60053);
        verifyNoInteractions(service);
    }

    /** history精确响应窗口沿冻结preset与anchor；不接受任意from/to，也不取Controller本机now。 */
    @Test void derivesHistoryWindowAndRejectsAdditionalQueryOrBody() throws Exception {
        when(service.history(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AppVersionedPropertyHistory("RAW", "RAW", "AVG", List.of()));
        var request = historyRequest();
        var result = mapper.readTree(controller.history(SHARE.toString(), DEVICE.toString(), "temperature", request).getBody());
        assertThat(result.path("from").asString()).isEqualTo("2026-09-07T11:00:00Z");
        assertThat(result.path("to").asString()).isEqualTo("2026-09-07T12:00:00Z");
        var shifted = historyRequest();
        shifted.addParameter("from", "2000-01-01T00:00:00Z");
        assertCode(() -> controller.history(SHARE.toString(), DEVICE.toString(), "temperature", shifted), 10001);
        var body = historyRequest();
        body.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertCode(() -> controller.history(SHARE.toString(), DEVICE.toString(), "temperature", body), 10001);
    }

    /** 事务代理或DTO投影的未预期异常也统一503/60055，不让GlobalExceptionHandler回通用500。 */
    @Test void mapsProxyAndProjectionFailureToShareDependencyError() {
        when(service.catalog(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("internal test failure"));
        var request = request("GET");
        request.addParameter("variableKey", "sensor");
        assertThatThrownBy(() -> controller.catalog(SHARE.toString(), request))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode().code()).isEqualTo(60055);
                    assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(failure.getMessage()).doesNotContain("internal test failure");
                });
    }

    /** 控制器层已建立Share属性只为测试包装行为，不表示网络令牌通过认证。 */
    private static MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/v1/shares/" + SHARE);
        request.setAttribute(DashboardSharePrincipal.class.getName(), new DashboardSharePrincipal(SHARE,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                Instant.now().plusSeconds(3600), "NONE", "a".repeat(64)));
        return request;
    }
    /** 历史合法query闭集，不带客户端from/to。 */
    private static MockHttpServletRequest historyRequest() {
        var request = request("GET");
        request.addParameter("expectedModelVersionId", MODEL.toString());
        request.addParameter("windowPreset", "LAST_1_HOUR");
        request.addParameter("anchorAt", "2026-09-07T12:00:00Z");
        request.addParameter("granularity", "RAW");
        request.addParameter("aggregation", "AVG");
        return request;
    }
    /** 合法告警原始信封，不依赖宽松对象序列化。 */
    private static byte[] alarmBody() {
        return ("{\"devices\":[{\"deviceId\":\"" + DEVICE + "\",\"expectedModelVersionId\":\"" + MODEL
                + "\"}],\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"MAJOR\"]}")
                .getBytes(StandardCharsets.UTF_8);
    }
    /** 超长type只能由本层替身构造，不声称可越过真实PG列约束。 */
    private static DashboardShareAlarmPage page(String type) {
        Instant time = Instant.parse("2026-09-07T00:00:00Z");
        return new DashboardShareAlarmPage(List.of(new AlarmDeviceQueryItem(UUID.randomUUID(), DEVICE, type,
                "MAJOR", "ACTIVE", "UNACKNOWLEDGED", time, null, null, null, time, 1)), null, false);
    }
    /** 保持确定错误码，不接受任意失败作为通过。 */
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(code));
    }
}
