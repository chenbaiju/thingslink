package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.springframework.core.io.Resource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509KeyManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 从 PEM 材料构建设备面 TLS 上下文。
 *
 * <p>设备密钥是明文凭据，TCP 平面必须走 TLS（接入合同 §3.3），因此本工厂**fail-closed**：材料缺失、不可读或
 * 格式非法一律抛出，让接入面拒绝启动，而不是悄悄退回明文监听——「配置不完整」绝不能表现为「照常可连」。</p>
 *
 * <p>只接受 PKCS#8 私钥（{@code BEGIN PRIVATE KEY}）与 X.509 证书；两种材料都按部署提供，值不入库、不入仓。
 * 支持 {@code classpath:} 与 {@code file:} 两种位置，便于部署按挂载卷提供证书，也让测试可用同一实现指向夹具。</p>
 *
 * <p><b>传输策略固定</b>（负责人 2026-09-18 裁决的 ②）：协议下限 TLS 1.2、上限 TLS 1.3，密码套件只允许 AEAD
 * （GCM／ChaCha20-Poly1305，且 TLS 1.2 只用 ECDHE 前向保密套件）。白名单在 JVM 实际支持的套件上取交集，
 * 交集中没有任何套件或连 TLS 1.2 都不支持时 <b>fail-closed</b>：宁可拒绝启动，也不接受一个比冻结更弱的
 * 密码学姿态。0-RTT／early data 由 JDK 的 {@code SSLSocket} 公开 API 根本不提供，因此本栈结构上不可能协商
 * 早数据重放面——这一点在设备接入资料里写明，而不是靠"没开"。</p>
 */
public class DeviceAccessTlsContextFactory {

    /** 私钥算法候选：先 EC（本阶段推荐），再 RSA，避免让部署去声明算法。 */
    private static final String[] KEY_ALGORITHMS = {"EC", "RSA"};

    /** 允许的协议：TLS 1.3 优先，TLS 1.2 作为设备兼容下限；更低版本一律不协商。 */
    private static final String[] ALLOWED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /** 允许的密码套件白名单：只保留 AEAD；TLS 1.2 只用 ECDHE（前向保密），不含 CBC 与静态 RSA 密钥交换。 */
    private static final String[] ALLOWED_CIPHER_SUITES = {
            // TLS 1.3
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            // TLS 1.2：ECDHE + AEAD
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"};

    /**
     * 构建服务端 TLS 上下文。
     *
     * @param certificateResource 证书链 PEM
     * @param privateKeyResource PKCS#8 私钥 PEM
     * @param keyPassword 私钥口令；无口令为空
     * @return 可创建服务端套接字的 TLS 上下文
     * @throws IllegalStateException 材料缺失、不可读或格式非法
     */
    public SSLContext create(Resource certificateResource, Resource privateKeyResource, String keyPassword) {
        try {
            KeyStore keyStore = buildKeyStore(certificateResource, privateKeyResource, keyPassword);
            char[] password = keyPassword == null ? new char[0] : keyPassword.toCharArray();
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("设备面 TLS 材料不可用：" + certificateResource + " / "
                    + privateKeyResource, exception);
        }
    }

    /**
     * 构建服务端证书与私钥的 JCA 密钥管理器。
     *
     * <p>DTLS（Scandium）需要 {@link X509KeyManager} 而不是 {@link SSLContext}，但证书材料的契约必须只有一份：
     * 因此这里与 {@link #create} 共用同一个 PKCS#12 KeyStore 构建路径，PEM 格式、口令与 fail-closed 行为完全一致。</p>
     *
     * @param certificateResource 证书链 PEM
     * @param privateKeyResource PKCS#8 私钥 PEM
     * @param keyPassword 私钥口令；无口令为空
     * @return 服务端 X.509 密钥管理器
     * @throws IllegalStateException 材料缺失、不可读或格式非法
     */
    public X509KeyManager serverKeyManager(Resource certificateResource, Resource privateKeyResource,
                                          String keyPassword) {
        try {
            KeyStore keyStore = buildKeyStore(certificateResource, privateKeyResource, keyPassword);
            char[] password = keyPassword == null ? new char[0] : keyPassword.toCharArray();
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keyStore, password);
            for (javax.net.ssl.KeyManager manager : managers.getKeyManagers()) {
                if (manager instanceof X509KeyManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("TLS 材料未生成 X.509 密钥管理器：" + certificateResource);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("设备面 TLS 材料不可用：" + certificateResource + " / "
                    + privateKeyResource, exception);
        }
    }

    /** 构建含服务端私钥与证书链的 PKCS#12 KeyStore；TLS 与 DTLS 共用这一条材料路径。 */
    private static KeyStore buildKeyStore(Resource certificateResource, Resource privateKeyResource,
                                          String keyPassword) throws IOException, GeneralSecurityException {
        Certificate[] chain = readCertificateChain(certificateResource);
        PrivateKey privateKey = readPrivateKey(privateKeyResource);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        char[] password = keyPassword == null ? new char[0] : keyPassword.toCharArray();
        keyStore.setKeyEntry("device-access", privateKey, password, chain);
        return keyStore;
    }

    /**
     * @return 白名单协议（高到低），供设备接入资料与用例引用同一份来源
     */
    public static List<String> allowedProtocols() {
        return List.of(ALLOWED_PROTOCOLS);
    }

    /**
     * @return 白名单密码套件，供设备接入资料与用例引用同一份来源
     */
    public static List<String> allowedCipherSuites() {
        return List.of(ALLOWED_CIPHER_SUITES);
    }

    /**
     * 生成设备面服务端套接字使用的固定 TLS 参数。
     *
     * <p>协议与套件都取「白名单 ∩ JVM 实际支持」：交集为空或缺少 TLS 1.2 时抛出，让接入面拒绝启动。客户端证书
     * 仍不要求（身份由应用层认证帧证明），因此这里显式关闭 want/need client auth。</p>
     *
     * @param context 由本工厂创建的上下文
     * @return 已固定的服务端参数
     * @throws IllegalStateException JVM 不支持白名单里的任何协议／套件
     */
    public SSLParameters pinnedServerParameters(SSLContext context) {
        SSLParameters supported = context.getSupportedSSLParameters();
        List<String> protocols = new ArrayList<>();
        for (String candidate : ALLOWED_PROTOCOLS) {
            if (Arrays.asList(supported.getProtocols()).contains(candidate)) {
                protocols.add(candidate);
            }
        }
        if (!protocols.contains("TLSv1.2")) {
            throw new IllegalStateException("JVM 不支持 TLS 1.2，拒绝以更弱协议启动设备面 TCP 接入");
        }
        List<String> ciphers = new ArrayList<>();
        List<String> supportedCiphers = Arrays.asList(supported.getCipherSuites());
        for (String candidate : ALLOWED_CIPHER_SUITES) {
            if (supportedCiphers.contains(candidate)) {
                ciphers.add(candidate);
            }
        }
        if (ciphers.isEmpty()) {
            throw new IllegalStateException("JVM 不支持任何白名单密码套件，拒绝启动设备面 TCP 接入");
        }
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(protocols.toArray(new String[0]));
        parameters.setCipherSuites(ciphers.toArray(new String[0]));
        // 身份由应用层认证帧证明（§3.3）：传输层不要求客户端证书，避免与业务身份混淆。
        parameters.setWantClientAuth(false);
        parameters.setNeedClientAuth(false);
        return parameters;
    }

    /** 读取证书链；PEM 中的注释行与分隔行都在解析前去掉，避免把部署备注当作内容。 */
    private static Certificate[] readCertificateChain(Resource resource) throws IOException, GeneralSecurityException {
        byte[] der = decodePem(resource, "CERTIFICATE");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return factory.generateCertificates(new java.io.ByteArrayInputStream(der))
                .toArray(new Certificate[0]);
    }

    /** 读取 PKCS#8 私钥；算法由材料本身决定，部署不需要额外声明。 */
    private static PrivateKey readPrivateKey(Resource resource) throws IOException, GeneralSecurityException {
        byte[] der = decodePem(resource, "PRIVATE KEY");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        GeneralSecurityException failure = null;
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException exception) {
                failure = exception;
            }
        }
        throw failure == null ? new GeneralSecurityException("私钥算法不受支持") : failure;
    }

    /** 去掉 PEM 注释与分隔行后做 base64 解码；材料缺失时直接失败，不返回空材料。 */
    private static byte[] decodePem(Resource resource, String type) throws IOException {
        if (resource == null || !resource.exists()) {
            throw new IOException("PEM 材料不存在：" + resource);
        }
        StringBuilder base64 = new StringBuilder();
        try (InputStream input = resource.getInputStream()) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-----")) {
                    continue;
                }
                base64.append(trimmed);
            }
        }
        if (base64.isEmpty()) {
            throw new IOException("PEM 材料为空：" + resource);
        }
        try {
            return Base64.getDecoder().decode(base64.toString());
        } catch (IllegalArgumentException exception) {
            throw new IOException("PEM 材料不是合法 base64（" + type + "）：" + resource, exception);
        }
    }
}
