package com.things.link.support.notification.delivery;

import com.things.link.support.notification.mail.LoggingMailSender;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.support.notification.mail.MailSender;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** 告警与规则共用真实邮件/Webhook 适配器的成功、配置失败、签名和 HTTP 分类测试。 */
class ExternalNotificationSenderTests {

    /** 未配置 SMTP 时开发日志实现不能冒充真实送达。 */
    @Test
    void treatsLoggingMailSenderAsRetryableFailure() {
        ExternalEmailNotificationSender sender =
                new ExternalEmailNotificationSender(new LoggingMailSender());

        assertThatThrownBy(() -> sender.send(emailRequest()))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason())
                            .isEqualTo(ExternalNotificationException.Reason.SMTP_UNCONFIGURED);
                    assertThat(failure.retryable()).isTrue();
                });
    }

    /** 真实邮件端口接收冻结快照，返回稳定 deliveryId 诊断值。 */
    @Test
    void sendsFrozenMailSnapshot() {
        AtomicReference<MailMessage> sent = new AtomicReference<>();
        MailSender mailSender = sent::set;
        ExternalNotificationRequest request = emailRequest();

        String providerId = new ExternalEmailNotificationSender(mailSender).send(request);

        assertThat(sent.get().to()).isEqualTo("ops@example.com");
        assertThat(sent.get().subject()).isEqualTo("设备告警");
        assertThat(sent.get().text()).isEqualTo("温度超过阈值");
        assertThat(providerId).isEqualTo("email:" + request.deliveryId());
    }

    /** Webhook 429 带完整防重放头并分类为可恢复失败。 */
    @Test
    void signsWebhookAndClassifiesRateLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                builder.build(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");
        ExternalNotificationRequest request = webhookRequest("https://8.8.8.8/hook");
        server.expect(requestTo(request.target()))
                .andExpect(header(
                        ExternalWebhookNotificationSender.DELIVERY_ID_HEADER,
                        request.deliveryId().toString()))
                .andExpect(header(
                        ExternalWebhookNotificationSender.SIGNATURE_HEADER,
                        org.hamcrest.Matchers.startsWith("v1=")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason())
                            .isEqualTo(ExternalNotificationException.Reason.WEBHOOK_RATE_LIMITED);
                    assertThat(failure.retryable()).isTrue();
                });
        server.verify();
    }

    /** 改用流式 exchange 后仍须保留 408 可重试、普通 4xx 永久失败的既有分类。 */
    @Test
    void preservesTimeoutAndClientErrorClassification() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                builder.build(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");
        server.expect(requestTo("https://8.8.8.8/timeout"))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        server.expect(requestTo("https://8.8.8.8/rejected"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertWebhookFailure(sender, "https://8.8.8.8/timeout",
                ExternalNotificationException.Reason.WEBHOOK_TIMEOUT, true);
        assertWebhookFailure(sender, "https://8.8.8.8/rejected",
                ExternalNotificationException.Reason.WEBHOOK_CLIENT_ERROR, false);
        server.verify();
    }

    /** 客户端创建不阻断 Bean 构造；首次瞬时失败可分类重试，下一次投递重新初始化并成功。 */
    @Test
    void lazilyRetriesHttpClientInitialization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicInteger attempts = new AtomicInteger();
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new UncheckedIOException(new IOException("loopback unavailable"));
                    }
                    return builder.build();
                },
                new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");
        ExternalNotificationRequest request = webhookRequest("https://8.8.8.8/hook");

        assertThat(attempts).hasValue(0);
        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason())
                            .isEqualTo(ExternalNotificationException.Reason.WEBHOOK_NETWORK_ERROR);
                    assertThat(failure.retryable()).isTrue();
                });
        server.expect(requestTo(request.target())).andRespond(withStatus(HttpStatus.OK));

        assertThat(sender.send(request)).isEqualTo("webhook:" + request.deliveryId());
        assertThat(attempts).hasValue(2);
        server.verify();
    }

    /** 恶意成功端即使持续返回正文，发送器也只能读取硬上限，且 2xx 仍保持原成功语义。 */
    @Test
    void capsOversizedSuccessfulWebhookResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LimitProbeInputStream maliciousBody = new LimitProbeInputStream();
        server.expect(requestTo("https://8.8.8.8/hook"))
                .andRespond(_request -> new MockClientHttpResponse(maliciousBody, HttpStatus.OK));
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                builder.build(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");
        ExternalNotificationRequest request = webhookRequest("https://8.8.8.8/hook");

        assertThat(sender.send(request)).isEqualTo("webhook:" + request.deliveryId());
        assertThat(maliciousBody.bytesRead())
                .isEqualTo(ExternalWebhookNotificationSender.MAX_RESPONSE_BODY_BYTES);
        assertThat(maliciousBody.closed()).isTrue();
        server.verify();
    }

    /** 恶意 5xx 正文同样先受硬上限约束，再沿用既有可重试服务端错误分类。 */
    @Test
    void capsOversizedErrorResponseBeforeClassifyingStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LimitProbeInputStream maliciousBody = new LimitProbeInputStream();
        server.expect(requestTo("https://8.8.8.8/hook"))
                .andRespond(_request -> new MockClientHttpResponse(
                        maliciousBody, HttpStatus.INTERNAL_SERVER_ERROR));
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                builder.build(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");

        assertThatThrownBy(() -> sender.send(webhookRequest("https://8.8.8.8/hook")))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason())
                            .isEqualTo(ExternalNotificationException.Reason.WEBHOOK_SERVER_ERROR);
                    assertThat(failure.retryable()).isTrue();
                });
        assertThat(maliciousBody.bytesRead())
                .isEqualTo(ExternalWebhookNotificationSender.MAX_RESPONSE_BODY_BYTES);
        assertThat(maliciousBody.closed()).isTrue();
        server.verify();
    }

    /** @return 完整邮件冻结请求 */
    private static ExternalNotificationRequest emailRequest() {
        return new ExternalNotificationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "ops@example.com",
                "设备告警", "温度超过阈值", Map.of());
    }

    /** SSRF 目标在发送前即被拒绝，不发起任何 HTTP。 */
    @Test
    void rejectsSsrUnsafeWebhookTargetWithoutSending() {
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                RestClient.create(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");

        for (String target : List.of(
                "https://127.0.0.1/hook",
                "https://169.254.169.254/hook",
                "https://[::1]/hook",
                "https://localhost/hook")) {
            ExternalNotificationRequest request = webhookRequest(target);
            assertThatThrownBy(() -> sender.send(request))
                    .as(target)
                    .isInstanceOf(ExternalNotificationException.class)
                    .satisfies(error -> {
                        ExternalNotificationException failure = (ExternalNotificationException) error;
                        assertThat(failure.reason())
                                .isEqualTo(ExternalNotificationException.Reason.INVALID_DELIVERY);
                        assertThat(failure.retryable()).isFalse();
                    });
        }
    }

    /** 域名无法解析归为可恢复网络错误，而非非法投递，维持「DNS 抖动可重试」语义。 */
    @Test
    void mapsUnresolvableHostToRetryableNetworkError() {
        ExternalWebhookNotificationSender sender = new ExternalWebhookNotificationSender(
                RestClient.create(), new ObjectMapper(),
                "test-webhook-signing-secret-at-least-32-bytes");
        ExternalNotificationRequest request = webhookRequest("https://nonexistent-host.invalid/hook");

        assertThatThrownBy(() -> sender.send(request))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason())
                            .isEqualTo(ExternalNotificationException.Reason.WEBHOOK_NETWORK_ERROR);
                    assertThat(failure.retryable()).isTrue();
                });
    }

    /** @return 完整 Webhook 冻结请求 */
    private static ExternalNotificationRequest webhookRequest(String target) {
        return new ExternalNotificationRequest(
                UUID.randomUUID(), UUID.randomUUID(), target,
                "设备告警", "温度超过阈值", Map.of("traceId", "trace-s9-3"));
    }

    /** 断言稳定失败原因与有限重试属性，避免每个 HTTP 状态测试复制转换代码。 */
    private static void assertWebhookFailure(ExternalWebhookNotificationSender sender, String target,
                                             ExternalNotificationException.Reason reason, boolean retryable) {
        assertThatThrownBy(() -> sender.send(webhookRequest(target)))
                .isInstanceOf(ExternalNotificationException.class)
                .satisfies(error -> {
                    ExternalNotificationException failure = (ExternalNotificationException) error;
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.retryable()).isEqualTo(retryable);
                });
    }

    /**
     * 模拟永不主动结束的恶意响应；读取超过生产硬上限就立即让测试失败。
     *
     * <p>不预分配大数组，测试自身同样保持常量内存。RestClient 必须在 exchange 回调后关闭本流。</p>
     */
    private static final class LimitProbeInputStream extends InputStream {

        /** 已被生产代码读取的字节数。 */
        private int bytesRead;

        /** RestClient 是否在回调结束后关闭了恶意响应流。 */
        private boolean closed;

        /** 单字节读取也受同一硬边界约束。 */
        @Override
        public int read() {
            requireWithinLimit(1);
            bytesRead++;
            return 'x';
        }

        /** 批量读取按调用方请求填充，但禁止累计越过生产上限。 */
        @Override
        public int read(byte[] target, int offset, int length) {
            requireWithinLimit(length);
            java.util.Arrays.fill(target, offset, offset + length, (byte) 'x');
            bytesRead += length;
            return length;
        }

        /** 记录响应确已关闭，防止只停止读取却继续占用连接。 */
        @Override
        public void close() {
            closed = true;
        }

        /** 任何越界读取都代表 D-052 保护失效。 */
        private void requireWithinLimit(int nextRead) {
            if (bytesRead + nextRead > ExternalWebhookNotificationSender.MAX_RESPONSE_BODY_BYTES) {
                throw new AssertionError("Webhook 响应读取超过硬上限");
            }
        }

        /** @return 当前累计读取字节 */
        private int bytesRead() {
            return bytesRead;
        }

        /** @return 响应流是否关闭 */
        private boolean closed() {
            return closed;
        }
    }
}
