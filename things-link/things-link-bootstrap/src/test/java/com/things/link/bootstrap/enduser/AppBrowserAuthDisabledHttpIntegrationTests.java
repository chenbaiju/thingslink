package com.things.link.bootstrap.enduser;

import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 禁用配置的真实HTTP精确路由验收，复用同一专用基础设施而不额外启动PG/Redis。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
class AppBrowserAuthDisabledHttpIntegrationTests {
    /** 真实随机端口，避免MockMvc绕开Servlet过滤器顺序。 */
    @Value("${local.server.port}") private int port;
    /** 普通APP数据源只检验无新会话，不提升业务连接权限。 */
    @Autowired private JdbcTemplate jdbc;
    /** 错误正文不包含认证秘密，只核固定业务码。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 仅三个精确POST禁用503；GET/邻接路径保持403且不能创建会话或Cookie。 */
    @Test void disabledEndpointsAndUnsupportedNeighborsRemainFailClosed() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM app_refresh_token", Integer.class);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (String endpoint : List.of("login", "refresh", "logout")) {
                verify(client, endpoint, "POST", 503, 60057);
                verify(client, endpoint, "GET", 403, 60028);
            }
            verify(client, "unknown", "POST", 403, 60028);
            verify(client, "login/extra", "POST", 403, 60028);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_refresh_token", Integer.class)).isEqualTo(before);
    }

    /** origin及JSON均合法，避免语法错误提前遮蔽禁用开关的预期分支。 */
    private void verify(HttpClient client, String endpoint, String method, int status, int code) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/app/browser-auth/" + endpoint))
                .timeout(Duration.ofSeconds(10)).header("Origin", "http://localhost:18766")
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.ofString("{}")); else request.GET();
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(code);
        assertThat(response.headers().allValues("Set-Cookie").size()).isZero();
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    /** 保留完全相同的真实专库与客户端连线，只以最后注册项覆盖默认开关。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        AppBrowserAuthHttpIntegrationTests.infrastructure(registry);
        registry.add("things-link.security.app-browser.enabled", () -> false);
    }
}
