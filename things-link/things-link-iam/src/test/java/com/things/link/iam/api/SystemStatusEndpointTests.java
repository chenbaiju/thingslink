package com.things.link.iam.api;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 系统状态接口真实 HTTP 与健康探针闭环测试。 */
@AutoConfigureMockMvc
@DisplayName("系统状态接口（S7-5）")
class SystemStatusEndpointTests extends AbstractIntegrationTest {

    /** 真实 MVC 请求入口。 */
    @Autowired
    private MockMvc mockMvc;

    /** 未登录不能借系统状态接口绕过控制台安全链。 */
    @Test
    @DisplayName("未登录请求返回 401")
    void rejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * IAM 隔离上下文装配了真实 PostgreSQL 和节点存储探针；Redis 健康自动配置由 bootstrap
     * 的完整 Actuator starter 提供，因此本模块只断言当前上下文确实注册的真实探针。
     */
    @Test
    @WithMockUser
    @DisplayName("登录后返回数据库与节点存储的实时脱敏状态")
    void returnsRealDependencyHealth() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.observedAt").isString())
                .andExpect(jsonPath("$.dependencies[*].code", hasItems("db", "diskSpace")))
                .andExpect(jsonPath("$.dependencies[?(@.code == 'db')].status", hasItems("UP")))
                .andExpect(jsonPath("$.dependencies[?(@.code == 'diskSpace')].status", hasItems("UP")));
    }
}
