package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.PreparedModelEvidence.Input;
import com.things.link.assistant.application.AnalysisReleaseReview;
import com.things.link.assistant.application.AnalysisResultIssuer;
import com.things.link.assistant.application.AnalysisResultPermit;
import com.things.link.assistant.application.AnalysisTransport;
import com.things.link.assistant.domain.AnalysisCall;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import tools.jackson.databind.json.JsonMapper;

/** 一次性内部分析客户端；不注册自动调用器，不提供权限、持久认领或模型准入。 */
public final class SingleInternalAnalysisClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BoundedAnalysisResolver RESOLVER = new BoundedAnalysisResolver(2,
            host -> InetAddress.getAllByName(host)[0]);
    private static final int MAX_BODY = 24 * 1024;
    private static final int MAX_HEADERS = 4 * 1024;
    private final URI origin;
    private final SSLContext context;
    private final AtomicBoolean used = new AtomicBoolean();

    /**
     * 绑定服务端固定内部目标及双向证书；构造期间不接收项目模型密钥。
     * @param origin 显式端口的HTTPS源地址，不能由用户请求指定，不允许路径、查询、片段或用户信息
     * @param identityFile 专用客户端身份库
     * @param identityPassword 身份库密码；内部复制在装配后清零
     * @param trustFile 服务端证书信任库
     * @param trustPassword 信任库密码；内部复制在装配后清零
     */
    public SingleInternalAnalysisClient(URI origin, Path identityFile, char[] identityPassword,
            Path trustFile, char[] trustPassword) {
        SSLContext selected = null;
        try {
            if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                    || origin.getPort() < 1 || origin.getPort() > 65535
                    || identityFile == null || trustFile == null || identityPassword == null || trustPassword == null)
                throw failure();
            selected = InternalAgentHealthClient.loadContext(identityFile, identityPassword, trustFile, trustPassword);
        } catch (Exception ignored) {
            // 不携带文件名、密码或底层证书异常。
        }
        if (selected == null) throw failure();
        this.origin = origin;
        this.context = selected;
    }

    /** 仅复用已验证的服务级证书上下文；不共享项目凭据、调用对象或一次性消费状态。 */
    private SingleInternalAnalysisClient(URI origin, SSLContext context) {
        this.origin = origin;
        this.context = context;
    }

    /** @return 使用固定目标和服务证书的全新单次客户端；不代表持久执行许可 */
    SingleInternalAnalysisClient newAttempt() { return new SingleInternalAnalysisClient(origin, context); }

    /**
     * 消费对象并发送一次固定内部请求；无重试、代理或重定向，错误不推断为零消费。
     * @param call 已派发的原始元数据；调用前仍需真实持久认领及当前权限复核
     * @param input 本次白名单证据投影，不接受任意原始业务对象
     * @param counterSha256 服务端受审计数指纹，不信任响应自报值
     * @param executionCandidateSha256 固定发布配置的执行候选指纹，不能由浏览器或内部响应指定
     * @param credential 本次独占可变凭据，复制后立即清零，所有退出均清零
     * @return 闭合校验后的未准入回执，不能直接认领业务成功或事实语义正确
     */
    public InternalAnalysisResultDecoder.Result execute(AnalysisCall call, Input input,
            String counterSha256, String executionCandidateSha256, byte[] credential) {
        return executeOnce(call, input, counterSha256, executionCandidateSha256, credential, null).result();
    }

    /**
     * 已持有独立裁决的本次内部调用；只接受受审v3回执并签发一次结果凭证。
     * @param call 已持久认领原调用
     * @param input 本次受控投影
     * @param counterSha256 独立计数候选
     * @param executionCandidateSha256 独立执行候选
     * @param credential 本次可变凭据，所有退出清零
     * @param approval 固定包内裁决产生的资格，不接受响应自报
     * @return 待最终确权的结果凭证，不是已提交的业务结果
     */
    public AnalysisTransport.Receipt executeReviewed(AnalysisCall call, Input input, String counterSha256,
            String executionCandidateSha256, byte[] credential, AnalysisReleaseReview.Approval approval) {
        if (approval == null) {
            if (credential != null) Arrays.fill(credential, (byte) 0);
            used.compareAndSet(false, true);
            throw failure();
        }
        return new AnalysisTransport.Receipt.Releasable(
                executeOnce(call, input, counterSha256, executionCandidateSha256, credential, approval).permit());
    }

    /** 本次结果与可选凭证只在栈内传递，不缓存正文或凭据。 */
    private record Outcome(InternalAnalysisResultDecoder.Result result, AnalysisResultPermit permit) {}

    /** 共用一次发送与清零路径；有审查资格时在连接前冻结签发绑定。 */
    private Outcome executeOnce(AnalysisCall call, Input input, String counterSha256,
            String executionCandidateSha256, byte[] credential, AnalysisReleaseReview.Approval approval) {
        AnalysisResultPermit permit = null;
        byte[] key = null, body = null, raw = null, responseBody = null, evidence = null;
        InternalAnalysisResultDecoder.Result result = null;
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "agent-analysis-deadline");
            thread.setDaemon(true);
            return thread;
        });
        Socket plain = null;
        SSLSocket peer = null;
        try {
            if (!used.compareAndSet(false, true) || credential == null || credential.length < 1 || credential.length > 4096)
                throw failure();
            for (byte value : credential) if (value < 33 || value > 126) throw failure();
            key = credential.clone();
            Arrays.fill(credential, (byte) 0);
            if (counterSha256 == null || !counterSha256.matches("[0-9a-f]{64}")
                    || executionCandidateSha256 == null || !executionCandidateSha256.matches("[0-9a-f]{64}")) throw failure();
            Deadline deadline = Deadline.until(call.deadline());
            body = InternalAnalysisRequestEncoder.encode(call, input);
            var envelope = JSON.readTree(body);
            String inputHash = envelope.get("inputSha256").asString();
            evidence = Base64.getDecoder().decode(envelope.get("inputBase64").asString());
            String requestHash = ModelRequestFingerprint.fingerprint(evidence);
            Arrays.fill(evidence, (byte) 0);
            AnalysisResultIssuer issuer = approval == null ? null : AnalysisResultIssuer.bind(call,
                    new AnalysisResultIssuer.Binding(inputHash, requestHash, counterSha256, executionCandidateSha256, input.evidenceIds()), approval);
            deadline.remainingNanos();
            Deadline connection = deadline.limit(TimeUnit.SECONDS.toNanos(5));
            InetAddress address = RESOLVER.resolve(origin.getHost(), connection.remainingNanos());
            plain = new Socket();
            Socket closing = plain;
            timer.schedule(() -> close(closing), deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            plain.connect(new InetSocketAddress(address, origin.getPort()), connection.remainingMillis());
            peer = (SSLSocket) context.getSocketFactory().createSocket(plain, origin.getHost(), origin.getPort(), true);
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            peer.setSSLParameters(parameters);
            peer.setSoTimeout(connection.remainingMillis());
            peer.startHandshake();
            connection.remainingNanos();
            peer.setSoTimeout(deadline.remainingMillis());
            var output = peer.getOutputStream();
            output.write(("POST /internal/analysis HTTP/1.1\r\nHost: " + origin.getRawAuthority()
                    + "\r\nContent-Type: application/json\r\nAccept: application/json\r\nAccept-Encoding: identity"
                    + "\r\nConnection: close\r\nContent-Length: " + body.length
                    + (approval == null ? "" : "\r\nX-Analysis-Review: " + approval.reviewSha256()) + "\r\nX-Model-Credential: ")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(key);
            output.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            raw = peer.getInputStream().readNBytes(MAX_HEADERS + 4 + MAX_BODY + 1);
            deadline.remainingNanos();
            responseBody = responseBody(raw);
            result = InternalAnalysisResultDecoder.decode(responseBody, call.id(), call.configurationRevision(),
                    inputHash, counterSha256, executionCandidateSha256, requestHash, input.evidenceIds(),
                    approval == null ? null : approval.reviewSha256());
            if (issuer != null) permit = issuer.issue(new AnalysisResultIssuer.Receipt(call.id(), call.configurationRevision(),
                    inputHash, result.requestSha256(), result.counterSha256(), result.executionCandidateSha256(),
                    result.releaseReviewSha256(), result.qualification(), result.status(), result.providerFingerprintSha256(),
                    result.content(), result.usage()));
            deadline.remainingNanos();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = null;
        } catch (Exception ignored) {
            result = null;
        } finally {
            close(peer);
            close(plain);
            timer.shutdownNow();
            for (byte[] value : new byte[][]{credential, key, body, raw, responseBody, evidence})
                if (value != null) Arrays.fill(value, (byte) 0);
        }
        if (result == null) throw failure();
        return new Outcome(result, permit);
    }

    /** 原截止时间只转换一次；之后只读取单调时钟，不因墙钟回拨延长。 */
    private record Deadline(long started, long budget) {
        static Deadline until(Instant end) {
            long started = System.nanoTime();
            long budget = Duration.between(Instant.now(), end).toNanos();
            if (budget <= 0 || budget > TimeUnit.SECONDS.toNanos(60)) throw failure();
            return new Deadline(started, budget);
        }
        long remainingNanos() {
            long elapsed = System.nanoTime() - started;
            long remaining = budget - elapsed;
            if (elapsed < 0 || remaining <= 0) throw failure();
            return remaining;
        }
        int remainingMillis() {
            return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos()));
        }
        Deadline limit(long maximum) {
            long started = System.nanoTime();
            return new Deadline(started, Math.min(maximum, remainingNanos()));
        }
    }

    /**
     * 验证固定响应分帧；关闭连接的正文最多二十四千字节，头部最多四千字节。
     * @param raw 有界原始HTTP字节，仅用于内部传输
     * @return 经头部、长度、编码检查后的JSON正文副本
     */
    static byte[] responseBody(byte[] raw) {
        if (raw.length > MAX_HEADERS + 4 + MAX_BODY) throw failure();
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == 13 && raw[i + 1] == 10 && raw[i + 2] == 13 && raw[i + 3] == 10) { split = i; break; }
        }
        if (split < 0 || split > MAX_HEADERS || raw.length - split - 4 > MAX_BODY) throw failure();
        for (int i = 0; i < split; i++) if (raw[i] != 13 && raw[i] != 10 && (raw[i] < 32 || raw[i] > 126)) throw failure();
        String[] lines = new String(raw, 0, split, StandardCharsets.US_ASCII).split("\r\n", -1);
        if (!lines[0].matches("HTTP/1\\.1 200 [ -~]*")) throw failure();
        var fields = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1 || !lines[i].substring(0, colon).matches("[A-Za-z0-9-]+")) throw failure();
            String value = lines[i].substring(colon + 1).trim();
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || fields.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), value) != null) throw failure();
        }
        String length = fields.get("content-length");
        if (!"application/json".equals(fields.get("content-type")) || !"no-store".equals(fields.get("cache-control"))
                || fields.containsKey("transfer-encoding")
                || !"identity".equals(fields.getOrDefault("content-encoding", "identity"))
                || length == null || !length.matches("[0-9]{1,5}")
                || Integer.parseInt(length) != raw.length - split - 4) throw failure();
        return Arrays.copyOfRange(raw, split + 4, raw.length);
    }

    /** 退出时关闭连接，不把关闭异常及远端信息送入日志或调用方。 */
    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    /** 统一固定异常；未知消费与服务拒绝均不伪装成零用量。 */
    private static IllegalStateException failure() { return new IllegalStateException("INTERNAL_ANALYSIS_FAILED"); }

    @Override public String toString() { return "内部单次分析客户端[内容已隐藏]"; }
}
