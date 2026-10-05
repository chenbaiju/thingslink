package com.things.link.assistant.infrastructure.transport;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 显式管理生命周期，仅访问固定健康检查目标；不自动注册到 Spring，也不发起付费调用。 */
public final class InternalAgentHealthClient implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI health;

    public static final class TransportException extends IllegalStateException {
        private TransportException() { super("INTERNAL_AGENT_HEALTH_FAILED"); }
    }

    /**
     * 创建固定目标的双向 TLS 健康客户端，禁止代理及重定向。
     * @param origin 内部 HTTPS 服务地址，不接受路径、用户信息、查询或片段
     * @param identityFile 客户端 PKCS12 身份库路径
     * @param identityPassword 客户端身份库密码
     * @param trustFile PKCS12 信任库路径
     * @param trustPassword 信任库密码
     */
    public InternalAgentHealthClient(URI origin, Path identityFile, char[] identityPassword,
            Path trustFile, char[] trustPassword) {
        if (origin == null || !"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                || origin.getPort() == 0 || origin.getPort() > 65535
                || identityFile == null || trustFile == null || identityPassword == null || trustPassword == null)
            throw new TransportException();
        SSLContext context = loadContext(identityFile, identityPassword, trustFile, trustPassword);
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        client = HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        health = origin.resolve("/internal/health");
    }

    /**
     * 校验身份及信任证书，装配双向 TLS；复制的密码在退出前清零。
     * @param identityFile 仅含一个客户端私钥条目的 PKCS12 身份库
     * @param identityPassword 身份库密码，不记录到日志或异常
     * @param trustFile 仅含有效 CA 证书条目的 PKCS12 信任库
     * @param trustPassword 信任库密码，不记录到日志或异常
     * @return 含客户端身份及服务端信任链的 TLS 上下文
     * @throws TransportException 配置读取或证书校验失败，仅返回固定错误码
     */
    static SSLContext loadContext(Path identityFile, char[] identityPassword,
            Path trustFile, char[] trustPassword) {
        char[] identityCopy = identityPassword.clone(), trustCopy = trustPassword.clone();
        SSLContext result = null;
        try {
            KeyStore identity = KeyStore.getInstance("PKCS12"), trust = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(identityFile)) { identity.load(input, identityCopy); }
            try (var input = Files.newInputStream(trustFile)) { trust.load(input, trustCopy); }
            if (identity.size() != 1 || trust.size() == 0) throw new GeneralSecurityException();
            String alias = identity.aliases().nextElement();
            if (!identity.isKeyEntry(alias) || !(identity.getKey(alias, identityCopy) instanceof PrivateKey)
                    || !(identity.getCertificate(alias) instanceof X509Certificate leaf)) throw new GeneralSecurityException();
            leaf.checkValidity();
            boolean[] usage = leaf.getKeyUsage();
            if (leaf.getBasicConstraints() != -1 || usage == null || !usage[0] || usage[5] || usage[6]
                    || !List.of("1.3.6.1.5.5.7.3.2").equals(leaf.getExtendedKeyUsage())) throw new GeneralSecurityException();
            for (var aliases = trust.aliases(); aliases.hasMoreElements();) {
                String trustedAlias = aliases.nextElement();
                if (!trust.isCertificateEntry(trustedAlias)
                        || !(trust.getCertificate(trustedAlias) instanceof X509Certificate ca)
                        || ca.getBasicConstraints() < 0) throw new GeneralSecurityException();
                ca.checkValidity();
            }
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity, identityCopy);
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            result = SSLContext.getInstance("TLS");
            result.init(keys.getKeyManagers(), managers.getTrustManagers(), new SecureRandom());
        } catch (Exception invalid) {
            // 禁止暴露可能包含文件名或凭据的密钥库异常。
            result = null;
        } finally {
            Arrays.fill(identityCopy, '\0'); Arrays.fill(trustCopy, '\0');
        }
        if (result == null) throw new TransportException();
        return result;
    }

    /**
     * 仅确认经过身份认证的健康检查契约，要求分析能力仍关闭，不证明模型分析已就绪。
     * @throws TransportException 连接、期限、响应范围或封闭健康字段不满足契约
     */
    public void verifyHealth() {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        boolean failed = false;
        try {
            var request = HttpRequest.newBuilder(health).GET().timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json").build();
            pending = client.sendAsync(request, ignored -> new BoundedBody());
            var response = pending.get(60, TimeUnit.SECONDS);
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                failed = true;
            else {
                JsonNode body = JSON.readTree(response.body());
                Set<String> fields = new HashSet<>();
                if (body != null && body.isObject()) body.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(Set.of("status", "analysisAvailable"))
                        || !body.get("status").isTextual() || !body.get("status").textValue().equals("ok")
                        || !body.get("analysisAvailable").isBoolean() || body.get("analysisAvailable").booleanValue())
                    failed = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); failed = true;
        } catch (Exception invalid) {
            failed = true;
        } finally {
            if (failed && pending != null) pending.cancel(true);
        }
        if (failed) throw new TransportException();
    }

    /** 最多接收一千零二十四字节的健康正文；超限先取消订阅，再返回固定异常。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription incoming) { subscription = incoming; incoming.request(1); }
        public void onNext(List<ByteBuffer> blocks) {
            if (result.isDone()) return;
            for (ByteBuffer block : blocks) {
                if (block.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new TransportException()); return;
                }
                byte[] received = new byte[block.remaining()]; block.get(received); bytes.writeBytes(received);
            }
            subscription.request(1);
        }
        public void onError(Throwable ignored) { result.completeExceptionally(new TransportException()); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @Override public void close() { client.shutdownNow(); }
    @Override public String toString() { return "InternalAgentHealthClient[REDACTED]"; }
}
