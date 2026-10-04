package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.support.DashboardApiAuthorization;
import com.things.link.dashboard.application.publication.DashboardShareConfiguration;
import com.things.link.dashboard.application.publication.DashboardShareConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 精确配置DTO及HTTP管理守卫；真实JWT/RLS验收在Bootstrap既有分享管理类。 */
class DashboardShareConfigurationControllerTests {
    /** 项目路径。 */ private final UUID project = UUID.randomUUID();
    /** 看板路径。 */ private final UUID dashboard = UUID.randomUUID();
    /** HTTP管理守卫替身。 */ private final DashboardApiAuthorization authorization = mock(DashboardApiAuthorization.class);
    /** 真实业务编排的出口替身。 */ private final DashboardShareConfigurationService service = mock(DashboardShareConfigurationService.class);
    /** 被测独立入口。 */ private final DashboardShareConfigurationController controller = new DashboardShareConfigurationController(authorization, service);

    /** 不可用仍显式输出三个null且禁止缓存，不返回secret、路径或内部摘要。 */
    @Test
    void exposesOnlyExactUnavailableFieldsAfterManagementGuard() {
        when(service.read(project, dashboard)).thenReturn(DashboardShareConfiguration.unavailable());
        var response = controller.configuration(project, dashboard, new MockHttpServletRequest());
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var json = new ObjectMapper().valueToTree(response.getBody());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("available", "hostOrigin", "hostVersion", "hostCompatibility");
        assertThat(json.path("available").asBoolean()).isFalse();
        for (String key : new String[] {"hostOrigin", "hostVersion", "hostCompatibility"}) assertThat(json.path(key).isNull()).isTrue();
        var order = inOrder(authorization, service);
        order.verify(authorization).requireManage(project);
        order.verify(service).read(project, dashboard);
    }

    /** 未冻结的query不得被静默忽略，也不调用部署读取。 */
    @Test
    void rejectsUnexpectedQuery() {
        var request = new MockHttpServletRequest(); request.setQueryString("include=path");
        assertThatThrownBy(() -> controller.configuration(project, dashboard, request))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        verifyNoInteractions(service);
    }
}
