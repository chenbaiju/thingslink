package com.things.link.ota.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ota.application.OtaInstallStopOperationCodec;
import com.things.link.ota.application.OtaInstallStopStatusQueryCodec;
import com.things.link.ota.application.OtaInstallStopPublisher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
public class EmqxOtaInstallStopPublisher implements OtaInstallStopPublisher {
    /** Broker信封只编码已经有界验真的Base64，不套用manifest的16KiB单字段预算。 */
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    /** 固定发布地址，配置无效时为空。 */ private final URI endpoint;
    /** 仅用于HTTP头的发布凭证。 */ private final String authorization;
    /** 自有活跃调用，访问受实例锁保护。 */ private final Set<Call> active = new HashSet<>();
    /** 惰性自有HTTP客户端。 */ private OkHttpClient client;
    /** 关闭后永久拒绝新调用。 */ private boolean closed;

    /** 复用受控Broker API身份，不接收请求指定地址。 */
    @org.springframework.beans.factory.annotation.Autowired
    public EmqxOtaInstallStopPublisher(
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
    @Override public Result publish(String kind, DeviceMqttDownlinkRoute route, byte[] canonical, Instant expiresAt, Instant operationDeadline, Duration budget) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OTA物理发送不能持有事务");
        }
        final long started = System.nanoTime();
        final Request request;
        final long nanos;
        try {
            if (budget == null || budget.isNegative() || budget.isZero()) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            Duration exchangeRoom = Duration.between(Instant.now(), expiresAt).minusSeconds(1);
            Duration effective = budget.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : budget;
            if (exchangeRoom.compareTo(effective) < 0) effective = exchangeRoom;
            if (effective.compareTo(Duration.ofMillis(1)) < 0) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            // 毫秒向下取整留出计算耗时，防止恰一秒TTL因几微秒开销降为零。
            nanos = TimeUnit.MILLISECONDS.toNanos(effective.toMillis());
            if (operationDeadline == null || !Instant.now().isBefore(operationDeadline))
                return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            long declaredExpiry;
            byte[] normalized;
            if ("OPERATION".equals(kind)) {
                var decoded = new OtaInstallStopOperationCodec().decode(canonical);
                declaredExpiry = decoded.value().expiresAt();
                normalized = decoded.canonical();
            } else if ("STATUS".equals(kind)) {
                var decoded = new OtaInstallStopStatusQueryCodec().decode(canonical);
                declaredExpiry = decoded.value().expiresAt();
                normalized = decoded.canonical();
            } else {
                throw new IllegalArgumentException();
            }
            if (!expiresAt.equals(Instant.ofEpochSecond(declaredExpiry)) || !Arrays.equals(canonical, normalized)) {
                throw new IllegalArgumentException();
            }
            // 从原地址剩余时间中扣除整个物理交换预算，Broker即使延迟接收也不延长许可排队。
            long expirySeconds = Duration.between(Instant.now(), expiresAt)
                    .minusNanos(Math.max(0, nanos - (System.nanoTime() - started))).getSeconds();
            if (expirySeconds < 1) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            String topic = route.internalTopic(topic(kind, route.projectKey(), route.deviceKey()));
            byte[] body = JSON.writeValueAsBytes(Map.of("topic", topic, "qos", 1L, "retain", false,
                    "payload", Base64.getEncoder().encodeToString(canonical), "payload_encoding", "base64",
                    "properties", Map.of("message_expiry_interval", Math.min(expirySeconds, 4294967295L))));
            if (!configured()) return result(Outcome.UNKNOWN, null, "CONFIGURATION_UNAVAILABLE");
            request = new Request.Builder().url(endpoint.toString()).header("Authorization", authorization)
                    .post(RequestBody.create(body, MediaType.get("application/json"))).build();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return result(Outcome.UNKNOWN, null, "INVALID_INSTALL_STOP_ENVELOPE");
        }
        final Call call;
        synchronized (this) {
            if (!configured()) return result(Outcome.UNKNOWN, null, "CONFIGURATION_UNAVAILABLE");
            if (client == null) client = new OkHttpClient.Builder()
                    .connectionPool(new okhttp3.ConnectionPool(2, 5, TimeUnit.SECONDS))
                    .retryOnConnectionFailure(false)
                    .followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS).build();
            long remaining = remaining(started, nanos, expiresAt, operationDeadline);
            if (remaining <= 0 || !Instant.now().isBefore(expiresAt)) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            AtomicBoolean exchanged = new AtomicBoolean();
            call = client.newBuilder().connectTimeout(Math.min(remaining, TimeUnit.SECONDS.toNanos(3)), TimeUnit.NANOSECONDS)
                    .callTimeout(remaining, TimeUnit.NANOSECONDS).readTimeout(remaining, TimeUnit.NANOSECONDS)
                    .writeTimeout(remaining, TimeUnit.NANOSECONDS)
                    .addNetworkInterceptor(chain -> {
                        // 即使客户端内部针对503等状态尝试follow-up，也不能产生第二次物理交换。
                        if (remaining(started, nanos, expiresAt, operationDeadline) <= 0)
                            throw new IOException("OTA安装前停止查询原期限已到");
                        if (!exchanged.compareAndSet(false, true)) throw new IOException("OTA安装前停止查询禁止隐式重发");
                        var response = chain.proceed(chain.request());
                        // OkHttp的503 follow-up不完全由连接重试开关控制；保留状态并禁止立即重试。
                        return response.code() == 503
                                ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                    }).build().newCall(request);
            active.add(call);
        }
        Integer status = null;
        try {
            long remaining = remaining(started, nanos, expiresAt, operationDeadline);
            if (remaining <= 0) return result(Outcome.UNKNOWN, null, "DEADLINE_EXPIRED");
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
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
            }
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
    /** 同时保留入口单调预算及原租约/许可绝对期限，暂停不刷新预算。 */
    private static long remaining(long started, long budget, Instant expiry, Instant operationDeadline) {
        Instant limit = expiry.isBefore(operationDeadline) ? expiry : operationDeadline;
        Duration wall = Duration.between(Instant.now(), limit);
        if (wall.isNegative() || wall.isZero()) return 0;
        long boundedWall = wall.compareTo(Duration.ofSeconds(5)) > 0
                ? TimeUnit.SECONDS.toNanos(5) : wall.toNanos();
        return Math.min(budget - (System.nanoTime() - started), boundedWall);
    }
    /** 精确受控路由，不接受分隔符或通配符。 */
    private static String topic(String kind, String project, String device) {
        if (project == null || device == null || !project.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || !device.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException();
        String suffix = switch (kind) {
            case "OPERATION" -> "operation";
            case "STATUS" -> "status/query";
            default -> throw new IllegalArgumentException();
        };
        return "tc/v1/" + project + "/" + device + "/down/ota/install-stop/" + suffix;
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
