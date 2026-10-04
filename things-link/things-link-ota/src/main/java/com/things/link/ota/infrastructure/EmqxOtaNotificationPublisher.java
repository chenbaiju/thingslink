package com.things.link.ota.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaNotificationCodec;
import com.things.link.ota.application.OtaNotificationPublisher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本域独立Broker发布器；默认重试和重定向关闭，不记录敏感供应商内容。 */
@Component
public class EmqxOtaNotificationPublisher implements OtaNotificationPublisher {
    /** 固定发布地址，配置无效时为空。 */ private final URI endpoint;
    /** 仅用于HTTP头的发布凭证。 */ private final String authorization;
    /** 自有活跃调用，访问受实例锁保护。 */ private final Set<Call> active = new HashSet<>();
    /** 惰性自有HTTP客户端。 */ private OkHttpClient client;
    /** 关闭后永久拒绝新调用。 */ private boolean closed;

    /** 复用受控Broker API身份，不接收请求指定地址。 */
    public EmqxOtaNotificationPublisher(
            @Value("${things-link.ingestion.emqx-api.base-url:http://localhost:18083}") String baseUrl,
            @Value("${things-link.ingestion.emqx-api.api-key:}") String apiKey,
            @Value("${things-link.ingestion.emqx-api.api-secret:}") String apiSecret) {
        endpoint = endpoint(baseUrl);
        authorization = apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()
                || apiKey.indexOf(':') >= 0 ? null : "Basic " + Base64.getEncoder().encodeToString(
                        (apiKey + ":" + apiSecret).getBytes(StandardCharsets.UTF_8));
    }
    /** 缺配置或已关闭时不能开始物理调用。 */
    @Override public synchronized boolean configured() { return !closed && endpoint != null && authorization != null; }
    /** 一次调用最多五秒且回执最多64KiB，不追踪重定向或自动重试。 */
    @Override public Result publish(DeviceMqttDownlinkRoute route, byte[] canonical, Duration budget) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OTA通知网络调用不能持有事务");
        }
        final long started = System.nanoTime();
        final Request request;
        final long nanos;
        try {
            if (budget == null || budget.isNegative() || budget.isZero()) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            nanos = budget.compareTo(Duration.ofSeconds(5)) > 0 ? TimeUnit.SECONDS.toNanos(5) : budget.toNanos();
            var codec = new OtaNotificationCodec();
            if (!Arrays.equals(canonical, codec.encode(codec.decode(canonical)))) throw new IllegalArgumentException();
            String topic = route.internalTopic(OtaNotificationCodec.topic(route.projectKey(), route.deviceKey()));
            byte[] body = new OtaCanonicalJson().writeObject(Map.of("topic", topic, "qos", 1L, "retain", false,
                    "payload", Base64.getEncoder().encodeToString(canonical), "payload_encoding", "base64"));
            if (!configured()) return result(Outcome.UNKNOWN, null, "CONFIGURATION_UNAVAILABLE");
            // 短时间批量发布复用连接；空闲超过五秒淘汰，传输不确定仍按UNKNOWN处理。
            request = new Request.Builder().url(endpoint.toString()).header("Authorization", authorization)
                    .post(RequestBody.create(body, MediaType.get("application/json"))).build();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return result(Outcome.UNKNOWN, null, "INVALID_NOTIFICATION");
        }
        final Call call;
        synchronized (this) {
            if (!configured()) return result(Outcome.UNKNOWN, null, "CONFIGURATION_UNAVAILABLE");
            if (client == null) client = new OkHttpClient.Builder()
                    .connectionPool(new okhttp3.ConnectionPool(2, 5, TimeUnit.SECONDS))
                    .retryOnConnectionFailure(false)
                    .followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS).build();
            long remaining = nanos - (System.nanoTime() - started);
            if (remaining <= 0) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            AtomicBoolean exchanged = new AtomicBoolean();
            call = client.newBuilder().connectTimeout(Math.min(remaining, TimeUnit.SECONDS.toNanos(3)), TimeUnit.NANOSECONDS)
                    .callTimeout(remaining, TimeUnit.NANOSECONDS).readTimeout(remaining, TimeUnit.NANOSECONDS)
                    .writeTimeout(remaining, TimeUnit.NANOSECONDS)
                    .addNetworkInterceptor(chain -> {
                        // 即使客户端内部针对503等状态尝试follow-up，也不能产生第二次物理交换。
                        if (!exchanged.compareAndSet(false, true)) throw new IOException("OTA通知禁止隐式重发");
                        var response = chain.proceed(chain.request());
                        // OkHttp的503 follow-up不完全由连接重试开关控制；保留状态并禁止立即重试。
                        return response.code() == 503
                                ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                    }).build().newCall(request);
            active.add(call);
        }
        Integer status = null;
        try (var response = call.execute()) {
            status = response.code();
            if (response.body() != null) {
                try (var input = response.body().byteStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer, 0, Math.min(buffer.length, 65537 - total))) != -1) {
                        total += read;
                        if (total > 65536) return result(Outcome.UNKNOWN, status, "RESPONSE_TOO_LARGE");
                    }
                }
            }
            return status >= 200 && status < 300 ? result(Outcome.BROKER_ACCEPTED, status, "HTTP_ACCEPTED")
                    : result(Outcome.REJECTED, status, "HTTP_REJECTED");
        } catch (IOException failure) {
            return result(Outcome.UNKNOWN, status, call.isCanceled() ? "CANCELLED_OR_TIMEOUT" : "TRANSPORT_UNKNOWN");
        } finally {
            synchronized (this) { active.remove(call); notifyAll(); }
        }
    }
    /** 直接取消真实网络Calls，调用线程的finally负责响应和槽位收束。 */
    @Override public synchronized void cancelActive() { active.forEach(Call::cancel); }
    /** 自有连接与dispatcher关闭；宿主执行器仍须等待publish实际返回。 */
    @Override @PreDestroy public synchronized void close() {
        closed = true;
        cancelActive();
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
    /** 地址仅取受控HTTP(S)主机，固定API路径，拒绝凭据及查询片段。 */
    private static URI endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return null;
            return uri.resolve("/api/v5/publish");
        } catch (IllegalArgumentException | NullPointerException invalid) { return null; }
    }
    /** 结果只包含固定分类和状态。 */
    private static Result result(Outcome outcome, Integer status, String reason) { return new Result(outcome, status, reason); }
}
