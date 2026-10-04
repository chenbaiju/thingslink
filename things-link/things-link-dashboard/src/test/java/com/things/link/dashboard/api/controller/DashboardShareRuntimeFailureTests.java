package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.application.DashboardShareContext;
import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.support.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC真实异常处理不能把事务代理或持久投影故障变成通用500，绕过分享依赖错误合同。 */
class DashboardShareRuntimeFailureTests {
    /** 两个用例入口均覆盖发生在应用方法守卫之前的事务创建故障。 */
    @Test
    void transactionProxyFailureRemainsShareDependencyUnavailable() throws Exception {
        var service = mock(DashboardShareRuntimeService.class);
        var principal = principal();
        when(service.context(principal)).thenThrow(new CannotCreateTransactionException("private-database-message"));
        when(service.schema(principal)).thenThrow(new CannotCreateTransactionException("private-database-message"));
        var mvc = MockMvcBuilders.standaloneSetup(new DashboardShareRuntimeController(service, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String endpoint : List.of("context", "schema")) {
            mvc.perform(get("/api/v1/shares/" + principal.shareId() + "/" + endpoint)
                            .requestAttr(DashboardSharePrincipal.class.getName(), principal))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(60055))
                    .andExpect(jsonPath("$.message").value("分享依赖暂不可用"));
        }
    }

    /** 已持久Host范围损坏发生在DTO投影，不应被通用异常处理吞成500或输出半个上下文。 */
    @Test
    void malformedPersistedHostProjectionRemainsShareDependencyUnavailable() throws Exception {
        var service = mock(DashboardShareRuntimeService.class);
        var principal = principal();
        var mapper = JsonMapper.builder().build();
        when(service.context(principal)).thenReturn(new DashboardShareContext(principal.shareId(), principal.dashboardId(),
                principal.dashboardVersionId(), 1, principal.expiresAt(), Instant.now(), mapper.createObjectNode(), List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new DashboardShareRuntimeController(service, mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/shares/" + principal.shareId() + "/context")
                        .requestAttr(DashboardSharePrincipal.class.getName(), principal))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(60055));
    }

    /** 仅模拟已通过前置安全链的内部身份，不用假Console用户扩大匿名权限。 */
    private static DashboardSharePrincipal principal() {
        return new DashboardSharePrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, Instant.now().plusSeconds(3600), "NONE", "0".repeat(64));
    }
}
