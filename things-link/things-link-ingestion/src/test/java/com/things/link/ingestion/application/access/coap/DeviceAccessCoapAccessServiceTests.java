package com.things.link.ingestion.application.access.coap;

import com.things.link.ingestion.application.access.DefaultDeviceAccessDeviceAuthenticator;
import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessAuthFailureReason;
import com.things.link.ingestion.application.access.DeviceAccessBusinessBudget;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.ingestion.application.access.DeviceAccessRateLimitedException;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse.CoapCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CoAP 访问决策层的契约矩阵（接入合同 §3.4／§3.5）。
 *
 * <p>这一层与 DTLS 传输解耦，因此响应码选错不必等到把 Scandium 接上才能发现。用例逐条钉住决策顺序与跨协议一致的
 * 映射：资源（4.04）、内容格式（4.15）、体积（4.13）、凭据存在性（4.01）、认证面预算（4.29）、唯一认证入口
 * （4.01／4.03）、业务预算（4.29）、未接线资源（5.01）。</p>
 */
class DeviceAccessCoapAccessServiceTests {

    /** 唯一认证入口替身。 */
    private DefaultDeviceAccessDeviceAuthenticator authenticator;

    /** 认证面预算替身。 */
    private DeviceAccessAuthBudget authBudget;

    /** 业务预算替身。 */
    private DeviceAccessBusinessBudget businessBudget;

    /** 业务处理器提供者替身（默认无实现）。 */
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DeviceAccessCoapResourceHandler> handlers =
            mock(ObjectProvider.class);

    /** 被测决策层。 */
    private DeviceAccessCoapAccessService service;

    /** 每个用例使用全新替身。 */
    @BeforeEach
    void setUp() {
        authenticator = mock(DefaultDeviceAccessDeviceAuthenticator.class);
        authBudget = mock(DeviceAccessAuthBudget.class);
        businessBudget = mock(DeviceAccessBusinessBudget.class);
        when(authBudget.check(anyString(), anyString(), anyString()))
                .thenReturn(DeviceAccessAuthBudget.Decision.ALLOW);
        when(handlers.iterator()).thenReturn(java.util.List.<DeviceAccessCoapResourceHandler>of().iterator());
        service = new DeviceAccessCoapAccessService(authenticator, authBudget, businessBudget, handlers);
    }

    /** 未冻结的资源路径一律 4.04，且不触碰认证与预算。 */
    @Test
    void unknownResourceIsNotFoundWithoutTouchingBudgets() {
        DeviceAccessCoapResponse response = service.handle(request("/device-access/v1/unknown", json(), "{}"));

        assertThat(response.code()).isEqualTo(CoapCode.NOT_FOUND);
        verify(authBudget, never()).check(anyString(), anyString(), anyString());
        verify(businessBudget, never()).charge(any(), any(), any());
    }

    /** 非 application/json 的载荷一律 4.15。 */
    @Test
    void nonJsonContentFormatIsRejected() {
        DeviceAccessCoapRequest request = new DeviceAccessCoapRequest(
                DeviceAccessCoapAccessService.PROPERTY_REPORT, 0, "{}".getBytes(StandardCharsets.UTF_8),
                "project/device", "secret", "10.0.0.1");

        assertThat(service.handle(request).code()).isEqualTo(CoapCode.UNSUPPORTED_CONTENT_FORMAT);
    }

    /** 超过 64 KiB 的载荷一律 4.13，且不进入认证。 */
    @Test
    void oversizedBodyIsRejectedBeforeAuthentication() {
        byte[] oversized = new byte[DeviceAccessCoapAccessService.MAX_BODY_BYTES + 1];

        DeviceAccessCoapResponse response = service.handle(new DeviceAccessCoapRequest(
                DeviceAccessCoapAccessService.PROPERTY_REPORT,
                DeviceAccessCoapAccessService.CONTENT_FORMAT_APPLICATION_JSON, oversized,
                "project/device", "secret", "10.0.0.1"));

        assertThat(response.code()).isEqualTo(CoapCode.REQUEST_ENTITY_TOO_LARGE);
        verify(authBudget, never()).check(anyString(), anyString(), anyString());
    }

    /** 缺少凭据选项是 4.01，且不扣预算（没有可归属的身份）。 */
    @Test
    void missingCredentialOptionsAreUnauthorized() {
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}",
                null, "secret")).code()).isEqualTo(CoapCode.UNAUTHORIZED);
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}",
                "project/device", null)).code()).isEqualTo(CoapCode.UNAUTHORIZED);
        verify(authBudget, never()).check(anyString(), anyString(), anyString());
    }

    /** deviceKey 必须形如 projectKey/deviceKey，否则 4.01。 */
    @Test
    void malformedDeviceKeyIsUnauthorized() {
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}",
                "no-separator", "secret")).code()).isEqualTo(CoapCode.UNAUTHORIZED);
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}",
                "project/", "secret")).code()).isEqualTo(CoapCode.UNAUTHORIZED);
    }

    /** 认证面预算拒绝与退避都是 4.29。 */
    @Test
    void authBudgetRejectionsAreTooManyRequests() {
        when(authBudget.check(anyString(), anyString(), anyString()))
                .thenReturn(DeviceAccessAuthBudget.Decision.RATE_LIMITED);
        assertThat(service.handle(request(DeviceAccessCoapAccessService.COMMAND_CLAIM, json(), "{}")).code())
                .isEqualTo(CoapCode.TOO_MANY_REQUESTS);

        when(authBudget.check(anyString(), anyString(), anyString()))
                .thenReturn(DeviceAccessAuthBudget.Decision.BACKOFF);
        assertThat(service.handle(request(DeviceAccessCoapAccessService.COMMAND_CLAIM, json(), "{}")).code())
                .isEqualTo(CoapCode.TOO_MANY_REQUESTS);
    }

    /** 凭据不匹配记入失败预算并回 4.01；平面未开通或项目不可写回 4.03。 */
    @Test
    void authenticationFailuresMapToFrozenCodes() {
        doThrow(new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.AUTH_FAILED))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}")).code())
                .isEqualTo(CoapCode.UNAUTHORIZED);
        verify(authBudget).recordFailure("project", "device");

        doThrow(new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PLANE_NOT_ENABLED))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}")).code())
                .isEqualTo(CoapCode.FORBIDDEN);

        doThrow(new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        assertThat(service.handle(request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}")).code())
                .isEqualTo(CoapCode.FORBIDDEN);
    }

    /** 认证成功：记成功预算、扣业务预算，且协议判定用 COAP（不是 HTTP／TCP）。 */
    @Test
    void authenticatedRequestChargesBusinessBudgetWithCoapProtocol() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        doReturn(new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 1))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());

        DeviceAccessCoapResponse response = service.handle(
                request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}"));

        assertThat(response.code()).as("业务未接线时必须回 5.01，不得伪造 2.04")
                .isEqualTo(CoapCode.NOT_IMPLEMENTED);
        verify(authBudget).recordSuccess("project", "device");
        verify(businessBudget).charge(tenantId, projectId, deviceId);
        verify(authenticator).authenticate(TransportProtocol.COAP, "project", "device", "secret");
    }

    /** 业务预算耗尽回 4.29，且不再进入业务处理。 */
    @Test
    void exhaustedBusinessBudgetIsTooManyRequests() {
        doReturn(new AuthenticatedDeviceIdentity(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 1))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        doThrow(new DeviceAccessRateLimitedException(Uuid7.generate()))
                .when(businessBudget).charge(any(), any(), any());

        assertThat(service.handle(request(DeviceAccessCoapAccessService.COMMAND_REPLY, json(), "{}")).code())
                .isEqualTo(CoapCode.TOO_MANY_REQUESTS);
    }

    /** 已接线的业务处理器结果原样透传（AX-4b 之后就是这条路径）。 */
    @Test
    void wiredHandlerResponseIsReturnedAsIs() {
        doReturn(new AuthenticatedDeviceIdentity(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 1))
                .when(authenticator).authenticate(any(), anyString(), anyString(), anyString());
        DeviceAccessCoapResourceHandler handler = mock(DeviceAccessCoapResourceHandler.class);
        when(handler.handle(any(), anyString(), any())).thenReturn(Optional.of(
                new DeviceAccessCoapResponse(CoapCode.CHANGED, "{}")));
        when(handlers.iterator()).thenReturn(java.util.List.of(handler).iterator());

        DeviceAccessCoapResponse response = service.handle(
                request(DeviceAccessCoapAccessService.PROPERTY_REPORT, json(), "{}"));

        assertThat(response.code()).isEqualTo(CoapCode.CHANGED);
        assertThat(response.body()).isEqualTo("{}");
    }

    /** @return application/json 的 Content-Format 号 */
    private static Integer json() {
        return DeviceAccessCoapAccessService.CONTENT_FORMAT_APPLICATION_JSON;
    }

    /** @return 使用默认 project/device 凭据的请求 */
    private static DeviceAccessCoapRequest request(String path, Integer contentFormat, String body) {
        return request(path, contentFormat, body, "project/device", "secret");
    }

    /** @return 指定凭据选项的请求 */
    private static DeviceAccessCoapRequest request(String path, Integer contentFormat, String body,
                                                  String deviceKey, String credential) {
        return new DeviceAccessCoapRequest(path, contentFormat, body.getBytes(StandardCharsets.UTF_8),
                deviceKey, credential, "10.0.0.1");
    }
}
