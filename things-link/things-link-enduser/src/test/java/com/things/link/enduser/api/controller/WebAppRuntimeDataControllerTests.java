package com.things.link.enduser.api.controller;

import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.enduser.api.dto.response.WebAppRuntimeDataResponse;
import com.things.link.enduser.api.support.WebAppRuntimeContextReader;
import com.things.link.enduser.api.support.WebAppRuntimeDataRequestParser;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.application.WebAppAlarmPage;
import com.things.link.enduser.application.WebAppRuntimeDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
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
import static org.mockito.Mockito.when;

/** App运行数据控制器最终UTF-8响应字节边界测试。 */
class WebAppRuntimeDataControllerTests {
    /** 五路响应共同上限；测试从公开HTTP行为验证该冻结值。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** 规范应用公开键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";
    /** 已验签身份租户。 */
    private static final UUID TENANT_ID = UUID.randomUUID();
    /** 已验签身份项目。 */
    private static final UUID PROJECT_ID = UUID.randomUUID();
    /** 已验签App用户。 */
    private static final UUID APP_USER_ID = UUID.randomUUID();
    /** 运行应用版本。 */
    private static final UUID APPLICATION_VERSION_ID = UUID.randomUUID();
    /** 运行看板版本。 */
    private static final UUID DASHBOARD_VERSION_ID = UUID.randomUUID();
    /** 告警来源设备。 */
    private static final UUID DEVICE_ID = UUID.randomUUID();
    /** 告警请求模型。 */
    private static final UUID MODEL_VERSION_ID = UUID.randomUUID();
    /** 真实Jackson UTF-8编码器。 */
    private ObjectMapper objectMapper;
    /** 业务编排替身只提供包装层不变量无法由持久约束构造的超大公开字符串。 */
    private WebAppRuntimeDataService service;
    /** 被测控制器。 */
    private WebAppRuntimeDataController controller;

    /** 每例重建编码器、替身和控制器，避免超大返回值跨例保留。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(WebAppRuntimeDataService.class);
        controller = new WebAppRuntimeDataController(new WebAppRuntimeDataRequestParser(), service, objectMapper);
    }

    /** 恰好4MiB的同一最终字节数组允许发送，中文按实际UTF-8字节计量且正文不裁剪。 */
    @Test
    void returnsExactFourMebibyteUtf8BytesWithoutTruncation() throws Exception {
        int fixedBytes = encodedSize("");
        int chineseBytes = encodedSize("中") - fixedBytes;
        String alarmType = "中" + "a".repeat(MAX_RESPONSE_BYTES - fixedBytes - chineseBytes);
        WebAppAlarmPage page = page(alarmType);
        byte[] expected = objectMapper.writeValueAsBytes(WebAppRuntimeDataResponse.AlarmPage.from(page));
        when(service.alarms(any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(page);

        byte[] body = controller.alarms(jwt(), requestBody(), request()).getBody();

        assertThat(chineseBytes).isEqualTo("中".getBytes(StandardCharsets.UTF_8).length);
        assertThat(expected).hasSize(MAX_RESPONSE_BYTES);
        assertThat(body).isEqualTo(expected);
        assertThat(alarmType.length()).isLessThan(alarmType.getBytes(StandardCharsets.UTF_8).length);
    }

    /** 多一个最终UTF-8字节必须整体失败，不能截断为4MiB成功正文。 */
    @Test
    void rejectsOneByteBeyondFourMebibytesInsteadOfTruncating() throws Exception {
        int fixedBytes = encodedSize("");
        String alarmType = "a".repeat(MAX_RESPONSE_BYTES - fixedBytes + 1);
        when(service.alarms(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(page(alarmType));

        assertThat(encodedSize(alarmType)).isEqualTo(MAX_RESPONSE_BYTES + 1);
        assertThatThrownBy(() -> controller.alarms(jwt(), requestBody(), request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4MiB");
    }

    /** 计算同一公共响应DTO仅替换告警类型后的最终JSON字节数。 */
    private int encodedSize(String alarmType) throws Exception {
        return objectMapper.writeValueAsBytes(WebAppRuntimeDataResponse.AlarmPage.from(page(alarmType))).length;
    }

    /** 包装层边界替身，不声称超长alarmType能越过生产持久列约束。 */
    private static WebAppAlarmPage page(String alarmType) {
        Instant time = Instant.parse("2026-09-07T00:00:00Z");
        return new WebAppAlarmPage(List.of(new AlarmDeviceQueryItem(
                UUID.randomUUID(), DEVICE_ID, alarmType, "MAJOR", "ACTIVE", "UNACKNOWLEDGED",
                time, null, null, null, time, 1)), null, false);
    }

    /** 构造包含可信三轴和项目代次的已验签App JWT。 */
    private static Jwt jwt() {
        return Jwt.withTokenValue("runtime-unit-token").header("alg", "HS256")
                .subject(APP_USER_ID.toString())
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, TENANT_ID.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_ID, PROJECT_ID.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_GENERATION, 1L)
                .build();
    }

    /** 构造四头齐全且无额外query的告警请求。 */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/app/alarms/query");
        request.addHeader(WebAppRuntimeContextReader.APPLICATION_KEY, APP_KEY);
        request.addHeader(WebAppRuntimeContextReader.APPLICATION_VERSION, APPLICATION_VERSION_ID.toString());
        request.addHeader(WebAppRuntimeContextReader.APPLICATION_REVISION, "1");
        request.addHeader(WebAppRuntimeContextReader.DASHBOARD_VERSION, DASHBOARD_VERSION_ID.toString());
        return request;
    }

    /** 告警信封使用真实严格解析器消费的规范字段闭集。 */
    private static byte[] requestBody() {
        return ("{\"devices\":[{\"deviceId\":\"" + DEVICE_ID + "\",\"expectedModelVersionId\":\""
                + MODEL_VERSION_ID + "\"}],\"conditionStates\":[\"ACTIVE\"],"
                + "\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"MAJOR\"]}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
