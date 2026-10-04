package com.things.link.bootstrap.security;

import com.things.link.iam.application.AuthRateLimiter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实socket与Boot/Tomcat装配测试；Redis仅替代计数存储，不冒充Redis集成资格。 */
class TrustedProxyHttpTests {

    private ServletWebServerApplicationContext start(String trusted) {
        Map<String, Object> properties = ProductionStartupTests.validProperties();
        properties.put("spring.config.name", "proxy-test-only");
        properties.put("server.port", "0");
        properties.put("server.address", "127.0.0.1");
        properties.put("spring.main.banner-mode", "off");
        properties.put("things-link.security.trusted-proxy-addresses", trusted);
        SpringApplication app = new SpringApplication(ProbeConfiguration.class);
        return (ServletWebServerApplicationContext) app.run(properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private HttpResponse<String> request(ServletWebServerApplicationContext context, String forwarded) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/probe"))
                    .header("X-Forwarded-For", forwarded).header("X-Forwarded-Proto", "https")
                    .header("Forwarded", "for=192.0.2.99;proto=https;host=evil.example")
                    .header("X-Forwarded-Host", "evil.example").GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void trustedClientsUseIndependentRealLimiterBucketsAndCannotSpoofLeftmostAddress() throws Exception {
        try (var context = start("127.0.0.1")) {
            for (int index = 0; index < 3; index++) {
                assertThat(request(context, "198.51.100.10").statusCode()).isEqualTo(200);
            }
            assertThat(request(context, "198.51.100.10").statusCode()).isEqualTo(429);
            HttpResponse<String> other = request(context, "198.51.100.11");
            assertThat(other.statusCode()).isEqualTo(200);
            assertThat(other.body()).isEqualTo("198.51.100.11|https|127.0.0.1");
            assertThat(request(context, "192.0.2.66, 198.51.100.10").statusCode()).isEqualTo(429);
        }
    }

    @Test
    void untrustedPeerCannotChangeIdentityProtocolOrHost() throws Exception {
        try (var context = start("192.0.2.10")) {
            assertThat(request(context, "198.51.100.10").body()).isEqualTo("127.0.0.1|http|127.0.0.1");
            request(context, "198.51.100.11");
            request(context, "198.51.100.12");
            assertThat(request(context, "198.51.100.13").statusCode()).isEqualTo(429);
        }
    }

    @Test
    void emptyTrustListDoesNotTrustLoopback() throws Exception {
        try (var context = start("")) {
            assertThat(request(context, "198.51.100.10").body()).isEqualTo("127.0.0.1|http|127.0.0.1");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TomcatServletWebServerAutoConfiguration.class)
    static class ProbeConfiguration {
        @Bean
        @SuppressWarnings("unchecked")
        ServletRegistrationBean<HttpServlet> probe() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            Map<String, Long> counts = new ConcurrentHashMap<>();
            when(redis.opsForValue()).thenReturn(values);
            when(values.increment(anyString())).thenAnswer(call -> counts.merge(call.getArgument(0), 1L, Long::sum));
            AuthRateLimiter limiter = new AuthRateLimiter(redis);
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    response.setStatus(limiter.tryAcquireRegistration(request.getRemoteAddr()) ? 200 : 429);
                    response.getWriter().write(request.getRemoteAddr() + "|" + request.getScheme() + "|" + request.getServerName());
                }
            }, "/probe");
        }
    }
}
