package com.things.link.ingestion.infrastructure.protocol.http;

import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 测试专用明文开关不能让远端或伪造转发头绕过 HTTPS。 */
class DeviceAccessHttpLoopbackTests {

    @Test
    void insecureTestSwitchRejectsNonLoopbackPeerBeforeCredentialValidation() throws Exception {
        DeviceAccessDeviceAuthenticator authenticator = mock(DeviceAccessDeviceAuthenticator.class);
        DeviceAccessAuthBudget budget = mock(DeviceAccessAuthBudget.class);
        var filter = new DeviceAccessHttpAuthenticationFilter(authenticator, budget, true);
        var request = new MockHttpServletRequest("POST", "/device-access/v1/property/report");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader(DeviceAccessHttpAuthenticationFilter.DEVICE_KEY_HEADER, "project/device");
        request.addHeader(DeviceAccessHttpAuthenticationFilter.DEVICE_SECRET_HEADER, "test-secret");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_REQUIRED");
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(authenticator, budget);
    }
}
