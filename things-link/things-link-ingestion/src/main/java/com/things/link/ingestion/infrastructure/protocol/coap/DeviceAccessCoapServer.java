package com.things.link.ingestion.infrastructure.protocol.coap;

import com.things.link.ingestion.application.access.coap.DeviceAccessCoapAccessService;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapRequest;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTlsContextFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.option.MapBasedOptionRegistry;
import org.eclipse.californium.core.coap.option.OpaqueOptionDefinition;
import org.eclipse.californium.core.coap.option.OptionRegistry;
import org.eclipse.californium.core.coap.option.StandardOptionRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.x509.KeyManagerCertificateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.X509KeyManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备面 CoAP/DTLS 接入端点（接入合同 §3.4）。
 *
 * <p>JDK 不提供 DTLS，因此本端点由 Californium（CoAP）＋Scandium（DTLS 1.2）承载；传输只做翻译，全部决策交给
 * {@link DeviceAccessCoapAccessService}。安全姿态与 TCP 平面逐条对齐：**只**注册 DTLS 端点（不添加明文 UDP 端点）、
 * 只启用冻结要求的 `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`、不要求也不接受客户端证书、**不启用 PSK**
 * （冻结§2 明确排除）；证书材料沿用与 TLS 完全相同的一份 PEM 契约（ECDSA P-256＋PKCS#8），缺失即拒绝启动。</p>
 *
 * <p>认证面与业务面预算不在这里实现：它们属于决策层，因此 CoAP 与 HTTP／TCP 共享同一份计数，换协议换不来新额度。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
@ConditionalOnProperty(prefix = "things-link.access.coap", name = "enabled", havingValue = "true")
public class DeviceAccessCoapServer {

    /** 只记录资源路径与拒绝原因，凭据与载荷永不进日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAccessCoapServer.class);

    /** 设备身份选项号（冻结 §3.4）。 */
    public static final int DEVICE_KEY_OPTION = 65001;

    /** 设备凭据选项号（冻结 §3.4）。 */
    public static final int CREDENTIAL_OPTION = 65002;

    /** 访问决策层。 */
    private final DeviceAccessCoapAccessService accessService;

    /** PEM 材料装载；与 TLS 平面共用同一份实现。 */
    private final DeviceAccessTlsContextFactory tlsContextFactory = new DeviceAccessTlsContextFactory();

    /** 监听端口；0 表示由系统分配（测试用）。 */
    private final int port;
    /** 默认只监听回环；容器部署须显式选择容器网络接口。 */
    @Value("${things-link.access.coap.bind-address:127.0.0.1}")
    private String bindAddress = "127.0.0.1";

    /** 证书链 PEM。 */
    private final Resource certificate;

    /** PKCS#8 私钥 PEM。 */
    private final Resource privateKey;

    /** 私钥口令；无口令为空。 */
    private final String keyPassword;

    /** 应答策略：分离响应（空 ACK＋独立业务响应）或捎带响应。 */
    private final DeviceAccessCoapResponder responder;

    /** CoAP 服务器。 */
    private CoapServer server;

    /** 端点配置：用于断言未改写冻结的客户端重传默认值（ACK_TIMEOUT／MAX_RETRANSMIT）。 */
    private Configuration configuration;

    /**
     * @param accessService CoAP 访问决策层
     * @param port 监听端口
     * @param certificate 证书链 PEM
     * @param privateKey PKCS#8 私钥 PEM
     * @param keyPassword 私钥口令
     * @param separateResponse 是否先回空 ACK 再单独回业务响应
     */
    public DeviceAccessCoapServer(DeviceAccessCoapAccessService accessService,
                                  @Value("${things-link.access.coap.port:5684}") int port,
                                  @Value("${things-link.access.tls.certificate:}") Resource certificate,
                                  @Value("${things-link.access.tls.private-key:}") Resource privateKey,
                                  @Value("${things-link.access.tls.key-password:}") String keyPassword,
                                  @Value("${things-link.access.coap.separate-response:true}")
                                  boolean separateResponse) {
        this.accessService = accessService;
        this.port = port;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.keyPassword = keyPassword;
        this.responder = new DeviceAccessCoapResponder(separateResponse);
    }

    /** 装配 DTLS 端点与三个冻结资源；证书不可用时 fail-closed（抛出即拒绝启动）。 */
    @PostConstruct
    void start() {
        if (certificate == null || !certificate.exists() || privateKey == null || !privateKey.exists()) {
            throw new IllegalStateException("CoAP 接入已启用但缺少 DTLS 材料："
                    + "things-link.access.tls.certificate / private-key 必须指向部署提供的 PEM");
        }
        X509KeyManager keyManager = tlsContextFactory.serverKeyManager(certificate, privateKey, keyPassword);
        InetAddress listenAddress;
        try {
            listenAddress = InetAddress.getByName(bindAddress);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("CoAP 监听地址不可解析：things-link.access.coap.bind-address", exception);
        }
        // RFC 7252 默认重传参数（ACK_TIMEOUT=2s／MAX_RETRANSMIT=4）属于设备侧行为，平台不得擅自改写：
        // 这里用 createStandardWithoutFile() 取标准默认值，全程不 set 这两项。
        configuration = Configuration.createStandardWithoutFile();
        DtlsConnectorConfig dtlsConfig = new DtlsConnectorConfig.Builder(configuration)
                // 只留冻结要求的套件：AEAD＋前向保密；不设置 PSK 存储，因此 PSK 握手在本端点不可能协商。
                .setAsList(DtlsConfig.DTLS_CIPHER_SUITES, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                .setCertificateIdentityProvider(new KeyManagerCertificateProvider(keyManager,
                        CertificateType.X_509))
                // 身份由应用层选项证明（§3.4），传输层既不要求也不索要客户端证书。
                .set(DtlsConfig.DTLS_CLIENT_AUTHENTICATION_MODE, CertificateAuthenticationMode.NONE)
                .setAddress(new InetSocketAddress(listenAddress, port))
                .build();
        server = new CoapServer(configuration);
        // 只注册 DTLS 端点：不调用任何添加明文 UDP 端点的入口，明文 CoAP 在本端口无法建连。
        server.addEndpoint(new CoapEndpoint.Builder()
                .setConfiguration(configuration)
                .setOptionRegistry(optionRegistry())
                .setConnector(new DTLSConnector(dtlsConfig))
                .build());
        // 资源按冻结的完整路径挂成树（/device-access/v1/...）；只挂叶子会让 URI-Path 与冻结路径不一致。
        Map<String, CoapResource> branches = new LinkedHashMap<>();
        for (String path : DeviceAccessCoapAccessService.RESOURCES) {
            String[] segments = path.replaceFirst("^/", "").split("/");
            CoapResource parent = null;
            StringBuilder prefix = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                prefix.append('/').append(segments[index]);
                boolean leaf = index == segments.length - 1;
                String key = prefix.toString();
                String segment = segments[index];
                CoapResource resource;
                if (leaf) {
                    resource = new DeviceAccessCoapResource(segment, path);
                } else {
                    resource = branches.get(key);
                    if (resource == null) {
                        // 新分支才挂载：共享前缀（device-access／v1）不能被重复挂到同一父级。
                        resource = new CoapResource(segment, true);
                        branches.put(key, resource);
                        attach(parent, resource);
                    }
                }
                if (leaf) {
                    attach(parent, resource);
                }
                parent = resource;
            }
        }
        server.start();
        LOGGER.info("设备面 CoAP/DTLS 接入已启动 port={} resources={}", boundPort(),
                DeviceAccessCoapAccessService.RESOURCES.size());
    }

    /** 停止端点并释放 DTLS 会话。 */
    @PreDestroy
    void stop() {
        if (server != null) {
            server.stop();
            server.destroy();
        }
    }

    /**
     * @return 实际绑定的端口；未启动时为 -1
     */
    public int boundPort() {
        if (server == null) {
            return -1;
        }
        return server.getEndpoints().stream()
                .map(endpoint -> endpoint.getAddress())
                .filter(address -> address != null)
                .mapToInt(InetSocketAddress::getPort)
                .findFirst()
                .orElse(-1);
    }

    /** 把资源挂到父级；父级为空表示挂在服务器根。 */
    private void attach(CoapResource parent, CoapResource resource) {
        if (parent == null) {
            server.add(resource);
        } else {
            parent.add(resource);
        }
    }

    /**
     * @return 认识两个冻结认证选项的注册表
     *
     * <p>Californium 默认注册表不认识 65001／65002，而 65001 是**关键**选项：未注册时端点会在业务前以
     * {@code 4.02 Bad Option} 拒绝请求。这里把两个选项声明为 opaque（冻结语义就是 opaque 字节），
     * 认证与预算判定仍由决策层完成。</p>
     */
    private static OptionRegistry optionRegistry() {
        return new MapBasedOptionRegistry(StandardOptionRegistry.getDefaultOptionRegistry(),
                new OpaqueOptionDefinition(DEVICE_KEY_OPTION, "TC-Device-Key"),
                new OpaqueOptionDefinition(CREDENTIAL_OPTION, "TC-Credential"));
    }

    /**
     * @return 平台侧 CoAP 的 ACK 超时（毫秒）
     */
    public int ackTimeoutMillis() {
        return configuration.getTimeAsInt(
                org.eclipse.californium.core.config.CoapConfig.ACK_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * @return 平台侧 CoAP 的最大重传次数
     */
    public int maxRetransmissions() {
        return configuration.get(org.eclipse.californium.core.config.CoapConfig.MAX_RETRANSMIT);
    }

    /** @return 当前已注册的端点数量；用于断言"只有 DTLS 端点" */
    public int endpointCount() {
        return server == null ? 0 : server.getEndpoints().size();
    }

    /**
     * 一个冻结资源：把 CoAP 报文翻译成决策层输入，再把决策结果翻译回 CoAP 响应。
     *
     * <p>资源本身不含任何业务或安全判断——判断全部在 {@link DeviceAccessCoapAccessService}，因此三个资源的
     * 行为不可能各自漂移。</p>
     */
    private final class DeviceAccessCoapResource extends CoapResource {

        /** 资源绑定的冻结路径；决策层按它判白名单，不依赖传输层拼出的 URI。 */
        private final String frozenPath;

        /**
         * @param name 资源名（URI 最后一段）
         * @param frozenPath 冻结资源路径
         */
        private DeviceAccessCoapResource(String name, String frozenPath) {
            super(name, true);
            this.frozenPath = frozenPath;
            getAttributes().setTitle(frozenPath);
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            DeviceAccessCoapResponse response = accessService.handle(request(exchange));
            // 应答策略（空 ACK 与业务结果分离）只有一份实现，三个资源共用。
            responder.answer(exchange, response);
        }

        /** 把 CoAP 报文翻译成决策层请求；路径用本资源绑定的冻结路径，不用传输层拼出的 URI。 */
        private DeviceAccessCoapRequest request(CoapExchange exchange) {
            String deviceKey = stringOption(exchange, DEVICE_KEY_OPTION);
            String credential = stringOption(exchange, CREDENTIAL_OPTION);
            return new DeviceAccessCoapRequest(frozenPath, exchange.getRequestOptions().getContentFormat(),
                    exchange.getRequestPayload(), deviceKey, credential,
                    String.valueOf(exchange.getSourceAddress()));
        }

        /** 读取自定义选项；缺失或非文本时为空。 */
        private String stringOption(CoapExchange exchange, int number) {
            List<Option> options = exchange.getRequestOptions().asSortedList();
            for (Option option : options) {
                if (option.getNumber() == number) {
                    return option.getStringValue();
                }
            }
            return null;
        }

    }
}
