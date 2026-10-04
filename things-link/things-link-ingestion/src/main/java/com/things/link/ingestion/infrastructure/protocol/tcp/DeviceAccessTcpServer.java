package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.access.DeviceAccessAccessPayloads;
import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessAuthFailureReason;
import com.things.link.ingestion.application.access.DeviceAccessBusinessBudget;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.application.access.DeviceAccessIdempotencyConflictException;
import com.things.link.ingestion.application.access.DeviceAccessRateLimitedException;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkMessage;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.application.DeviceCommandAccessReplyPort;
import com.things.link.telemetry.application.DeviceCommandRedeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;

/**
 * 设备面 TCP/TLS 接入服务器：只交付「TLS 连接 + 认证握手 + 会话事实」。
 *
 * <p>首阶段用 JDK 的 {@link SSLServerSocket} 与 Java 21 虚拟线程，而不是引入事件循环框架：接入合同 §6 把每设备
 * 的 TCP 会话限为 1，阻塞式按帧读取在这种规模下足够，且不新增依赖；真正需要吞吐与背压时（G3 容量切片）再评估
 * 分帧框架，届时复用同一编解码与会话端口。</p>
 *
 * <p>认证顺序与 HTTP 平面一致：解析凭据 → 交给唯一的设备认证入口（它完成凭据校验、平面资格、项目可写与共享
 * 预算扣减）→ 建立会话（代次与配置代次入库，接管既有活跃会话）→ 回 {@code AUTH_RESPONSE}。认证与平面拒绝
 * 都回 {@code ERROR} 帧并关闭连接，且拒绝原因使用冻结合同里的错误码。</p>
 *
 * <p>{@code UPLINK} 与 {@code REPLY} 直接复用与 HTTP 相同的受理与回复用例，因此预算、幂等、命令终态与计量口径
 * 在两种协议上完全一致。受理用例成功返回后写 {@code ACCEPTED}，失败返回稳定错误码。
 * 应答只表示接入受理，不等于下游处理或设备执行成功（ADR 0141）。</p>
 *
 * <p>{@code DOWNLINK} 是平台到设备的方向，设备发来一律按线格式/方向违规回 {@code FRAME_INVALID}；命令推送由
 * 平台侧会话注册表在 AX-3c 后续增量交付。</p>
 *
 * <p><b>连接与握手预算</b>（§6「另设握手、连接预算」）：本实例接受的连接数有上限
 * （{@code things-link.access.tcp.max-connections}，默认 10000），超出时立即回 {@code ERROR(RATE_LIMITED)}
 * 并关闭，避免半开连接把进程资源吃光；从接受连接到首帧必须在
 * {@code things-link.access.tcp.handshake-timeout-seconds}（默认 10 秒，不得大于失效窗口）内完成，
 * 因此慢握手（slowloris）只占用一个有界额度、不会无限期占位。两个键都是字段级补充（§6 冻结了预算种类与
 * 「不额外放宽」的口径，未冻结实例级数值），在此公开说明。</p>
 *
 * <p><b>错误帧与关闭的对应关系</b>（§3.5 错误码表里标注了「＋关闭」的那些）：认证类（{@code AUTH_REQUIRED}、
 * {@code AUTH_FAILED}、{@code CREDENTIAL_*}、{@code DEVICE_DISABLED}、{@code PROJECT_UNAVAILABLE}）与帧级
 * 违规（{@code FRAME_INVALID}：魔数/版本/flags/长度/类型/方向）回一帧 {@code ERROR} 后关闭；业务类
 * （{@code PAYLOAD_INVALID}、{@code IDEMPOTENCY_CONFLICT}、{@code COMMAND_NOT_FOUND}、
 * {@code RATE_LIMITED}、{@code HANDOFF_UNAVAILABLE}）回一帧 {@code ERROR} 后保持连接。超长帧在拆帧时就表现为
 * 长度违规，因此设备侧看到的是 {@code FRAME_INVALID} 而不是 {@code PAYLOAD_TOO_LARGE}（§3.3 允许二者同义）。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
@ConditionalOnProperty(prefix = "things-link.access.tcp", name = "enabled", havingValue = "true")
public class DeviceAccessTcpServer implements org.springframework.context.SmartLifecycle {

    /** 只记录标识与拒绝原因，凭据与载荷永不进日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAccessTcpServer.class);

    /** 设备认证与预算入口。 */
    private final DeviceAccessDeviceAuthenticator authenticator;

    /** 接入配置与会话事实端口。 */
    private final DeviceAccessSessionPort sessionPort;

    /** 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /** TLS 上下文工厂。 */
    private final DeviceAccessTlsContextFactory tlsContextFactory = new DeviceAccessTlsContextFactory();

    /** 监听端口；0 表示由系统分配（测试用）。 */
    private final int port;
    /** 默认只监听回环；容器部署须显式选择容器网络接口。 */
    @Value("${things-link.access.tcp.bind-address:127.0.0.1}")
    private String bindAddress = "127.0.0.1";

    /** 承载会话的实例标识，写入会话事实供跨实例路由与接管判定。 */
    private final String ownerInstance;

    /** 证书链与私钥位置；由部署提供，不入库不入仓。 */
    private final Resource certificate;

    /** 私钥位置。 */
    private final Resource privateKey;

    /** 私钥口令；无口令为空。 */
    private final String keyPassword;

    /** 会话保活判据：心跳周期与 3 周期失效窗口。 */
    private final DeviceAccessTcpKeepAlive keepAlive;

    /** 与 HTTP 共用的上行受理用例。 */
    private final DeviceAccessUplinkIngressService uplinkIngress;

    /** 与 HTTP 共用的命令回复入口。 */
    private final DeviceCommandAccessReplyPort commandReply;

    /** 本实例活跃会话注册表：下行推送按设备找当前代次的写句柄。 */
    private final DeviceAccessTcpSessionRegistry sessionRegistry;

    /** 重连补投端口：把该设备因离线而未投递的命令从退避提前到当前时刻。 */
    private final DeviceCommandRedeliveryPort redeliveryPort;

    /** 连接预算指标：接受连接时计数，因此慢握手同样占用额度。 */
    private final DeviceAccessTcpMetrics metrics;

    /** 认证面预算（每 IP 窗口＋每设备失败窗口与退避）：与 HTTP 同一份实现，换协议不换额度。 */
    private final DeviceAccessAuthBudget authBudget;

    /** 业务请求预算：TCP 一次连接只认证一次，因此按业务帧扣。 */
    private final DeviceAccessBusinessBudget businessBudget;

    /** 本实例允许同时接受的连接数上限。 */
    private final int maxConnections;

    /** 从接受到首帧（TLS 握手＋认证帧）的时间预算。 */
    private final java.time.Duration handshakeTimeout;

    /** 本实例拥有的socket，停机先关闭I/O让finally能在未中断线程上收敛会话事实。 */
    private final java.util.Set<Socket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 接受连接的虚拟线程执行器。 */
    private ExecutorService connections;

    /** 广播消费就绪门禁，先启动Kafka再开放设备接入。 */
    private final TcpDownlinkReadiness readiness;
    /** Spring生命周期状态。 */
    private volatile boolean running;

    /** 已绑定的服务端套接字。 */
    private SSLServerSocket serverSocket;

    /**
     * @param authenticator 设备认证与预算入口
     * @param sessionPort 接入配置与会话事实端口
     * @param objectMapper 统一 JSON 映射器
     * @param port 监听端口
     * @param runtime 承载会话的运行身份
     * @param certificate 证书链 PEM
     * @param privateKey PKCS#8 私钥 PEM
     * @param keyPassword 私钥口令
     * @param heartbeat 心跳周期；不得小于 10 秒
     * @param uplinkIngress 与 HTTP 共用的上行受理用例
     * @param commandReply 与 HTTP 共用的命令回复入口
     * @param sessionRegistry 本实例活跃会话注册表
     * @param redeliveryPort 重连补投端口
     * @param metrics 连接预算指标
     * @param authBudget 认证面预算
     * @param businessBudget 业务请求预算
     * @param maxConnections 本实例连接数上限；必须大于零
     * @param handshakeTimeoutSeconds 首帧（TLS 握手＋认证帧）时间预算秒数；必须大于零且不大于失效窗口
     */
    public DeviceAccessTcpServer(DeviceAccessDeviceAuthenticator authenticator,
                                 DeviceAccessSessionPort sessionPort,
                                 ObjectMapper objectMapper,
                                 @Value("${things-link.access.tcp.port:8883}") int port,
                                 DeviceAccessTcpRuntime runtime,
                                 @Value("${things-link.access.tls.certificate:}") Resource certificate,
                                 @Value("${things-link.access.tls.private-key:}") Resource privateKey,
                                 @Value("${things-link.access.tls.key-password:}") String keyPassword,
                                 @Value("${things-link.access.tcp.heartbeat-seconds:30}") long heartbeatSeconds,
                                 DeviceAccessUplinkIngressService uplinkIngress,
                                 DeviceCommandAccessReplyPort commandReply,
                                 DeviceAccessTcpSessionRegistry sessionRegistry,
                                 DeviceCommandRedeliveryPort redeliveryPort,
                                 DeviceAccessTcpMetrics metrics,
                                 DeviceAccessAuthBudget authBudget,
                                 DeviceAccessBusinessBudget businessBudget,
                                 @Value("${things-link.access.tcp.max-connections:10000}") int maxConnections,
                                 @Value("${things-link.access.tcp.handshake-timeout-seconds:10}")
                                 long handshakeTimeoutSeconds, TcpDownlinkReadiness readiness) {
        this.authenticator = authenticator;
        this.sessionPort = sessionPort;
        this.objectMapper = objectMapper;
        this.port = port;
        this.ownerInstance = runtime.id();
        this.readiness = readiness;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.keyPassword = keyPassword;
        this.keepAlive = new DeviceAccessTcpKeepAlive(java.time.Duration.ofSeconds(heartbeatSeconds));
        this.uplinkIngress = uplinkIngress;
        this.commandReply = commandReply;
        this.sessionRegistry = sessionRegistry;
        this.redeliveryPort = redeliveryPort;
        this.metrics = metrics;
        this.authBudget = authBudget;
        this.businessBudget = businessBudget;
        this.maxConnections = maxConnections;
        this.handshakeTimeout = java.time.Duration.ofSeconds(handshakeTimeoutSeconds);
    }

    /** 绑定 TLS 监听并开始接受连接；材料不可用时 fail-closed（抛出即拒绝启动）。 */
    @Override
    public void start() {
        if (certificate == null || !certificate.exists() || privateKey == null || !privateKey.exists()) {
            throw new IllegalStateException("TCP 接入已启用但缺少 TLS 材料："
                    + "things-link.access.tls.certificate / private-key 必须指向部署提供的 PEM");
        }
        if (maxConnections < 1) {
            throw new IllegalStateException("TCP 连接预算必须大于零：things-link.access.tcp.max-connections");
        }
        if (handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
            throw new IllegalStateException("TCP 握手预算必须大于零："
                    + "things-link.access.tcp.handshake-timeout-seconds");
        }
        if (handshakeTimeout.compareTo(keepAlive.window()) > 0) {
            // 大于失效窗口时握手超时永远不会先触发，配置会表现为「配了但没生效」，必须 fail-fast。
            throw new IllegalStateException("TCP 握手预算不得大于心跳失效窗口："
                    + "things-link.access.tcp.handshake-timeout-seconds <= 3 × 心跳周期");
        }
        SSLContext context = tlsContextFactory.create(certificate, privateKey, keyPassword);
        readiness.awaitReady();
        try {
            serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            // 传输策略固定（TLS 1.2 下限、仅 AEAD 套件）在工厂里 fail-closed 算好，这里原样套到监听上。
            serverSocket.setSSLParameters(tlsContextFactory.pinnedServerParameters(context));
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            connections = Executors.newVirtualThreadPerTaskExecutor();
            running = true;
            Thread acceptLoop = Thread.ofVirtual().name("device-access-tcp-accept").start(this::acceptLoop);
            LOGGER.info("设备面 TCP/TLS 接入已启动 port={} owner={}", boundPort(), ownerInstance);
            acceptLoop.setUncaughtExceptionHandler((thread, failure) ->
                    LOGGER.error("设备面 TCP 接受循环异常退出", failure));
        } catch (IOException exception) {
            throw new IllegalStateException("设备面 TCP 监听绑定失败", exception);
        }
    }

    /** 关闭监听与全部承载连接。 */
    @Override
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException exception) {
            LOGGER.warn("关闭设备面 TCP 监听失败", exception);
        }
        sockets.forEach(socket -> closeQuietly(socket));
        if (connections != null) {
            connections.shutdown();
            try {
                if (!connections.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) connections.shutdownNow();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                connections.shutdownNow();
            }
        }
    }

    /** Kafka注册表phase为MAX-100；服务器后启先停。 */
    @Override public int getPhase() { return Integer.MAX_VALUE - 50; }
    /** @return 当前生命周期状态 */
    @Override public boolean isRunning() { return running; }

    /**
     * 实际绑定的端口；配置为 0 时由系统分配，测试据此连接。
     *
     * @return 监听端口
     */
    public int boundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** 接受连接；每条连接一个虚拟线程，异常只影响该连接。 */
    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                if (!running || !readiness.ready()) { closeQuietly(socket); sockets.remove(socket); continue; }
                if (metrics.enterConnection() > maxConnections) {
                    // 拒绝也必须落账并归还额度，否则一次拒绝会永久占掉一个名额。
                    metrics.leaveConnection();
                    metrics.recordRejectedConnection();
                    // 拒绝要走自己的虚拟线程：过载时最不该发生的事就是接受循环被一个慢客户端的握手卡住。
                    try {
                        connections.execute(() -> {
                            try { rejectOverCapacity(socket); } finally { sockets.remove(socket); }
                        });
                    } catch (RuntimeException exception) {
                        closeQuietly(socket);
                        sockets.remove(socket);
                    }
                    continue;
                }
                try {
                    connections.execute(() -> {
                        // 设备面入口按 ADR 0045 走数据池：连接线程没有过滤器链，这里就是入口（D-196）。
                        try (DatabaseWorkloadContext.Scope ignored =
                                     DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
                            handle(socket);
                        } finally {
                            sockets.remove(socket);
                            metrics.leaveConnection();
                        }
                    });
                } catch (RuntimeException exception) {
                    // 执行器已关闭时不能让额度泄漏；连接交回关闭路径。
                    metrics.leaveConnection();
                    closeQuietly(socket);
                    sockets.remove(socket);
                    throw exception;
                }
            } catch (IOException exception) {
                if (!serverSocket.isClosed()) {
                    LOGGER.warn("设备面 TCP 接受连接失败", exception);
                }
            }
        }
    }

    /**
     * 超出连接预算时的拒绝：尽力回一帧 {@code ERROR(RATE_LIMITED)} 再关闭。
     *
     * <p>裸断开会把「平台过载」和「网络不通」混为一谈；回一帧稳定错误码能让设备按 §6 的退避重连。写帧可能
     * 触发 TLS 握手，因此仍受握手预算约束，失败只记日志——设备已经收不到任何东西，不值得再抛异常。</p>
     *
     * @param socket 已接受但超过预算的连接
     */
    private void rejectOverCapacity(Socket socket) {
        LOGGER.debug("设备面 TCP 连接超出预算被拒 remote={} max={}", socket.getRemoteSocketAddress(), maxConnections);
        try (socket) {
            socket.setSoTimeout((int) handshakeTimeout.toMillis());
            writeError(socket.getOutputStream(), "RATE_LIMITED", "接入实例连接数已达上限，请稍后重连");
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("设备面 TCP 过载拒绝帧未能送达", exception);
        }
    }

    /** 关闭套接字并吞掉关闭异常；只用于已经无法继续服务的连接。 */
    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            LOGGER.debug("设备面 TCP 连接关闭失败", exception);
        }
    }

    /**
     * @return 当前本实例已接受的连接数（含握手中）
     */
    public int activeConnectionCount() {
        return metrics.activeConnections();
    }

    /** 单连接生命周期：认证握手 → 会话事实 → 帧循环 → 关闭会话。 */
    private void handle(Socket socket) {
        String remoteAddress = String.valueOf(socket.getRemoteSocketAddress());
        DeviceAccessSessionPort.Established established = null;
        DeviceAccessTcpConnection connection = null;
        AuthenticatedDeviceIdentity identity = null;
        String closeReason = "client_disconnect";
        try (socket) {
            // 认证阶段先套用握手预算：慢握手与「连上不发帧」只占用有界额度，不占用 3 周期失效窗口。
            socket.setSoTimeout((int) handshakeTimeout.toMillis());
            OutputStream output = socket.getOutputStream();
            DeviceAccessTcpFrameReader reader = new DeviceAccessTcpFrameReader(socket.getInputStream());
            try {
                // 首帧也必须在内部 try 里读：线格式错误要回 ERROR(FRAME_INVALID)，而不是静默断开。
                DeviceAccessTcpFrameCodec.DecodedFrame first = reader.readFrame();
                if (first == null) {
                    return;
                }
                if (first.type() != DeviceAccessTcpFrameType.AUTH_REQUEST) {
                    // 认证前只接受 AUTH_REQUEST（§3.3）。
                    writeError(output, "AUTH_REQUIRED", "认证前只接受 AUTH_REQUEST");
                    closeReason = "auth_required";
                    return;
                }
                AuthRequest request;
                try {
                    request = objectMapper.readValue(first.payload(), AuthRequest.class);
                    if (request == null) {
                        throw new IllegalArgumentException("AUTH_REQUEST 必须为 JSON 对象");
                    }
                } catch (RuntimeException exception) {
                    writeError(output, "FRAME_INVALID", "AUTH_REQUEST 载荷不是合法 JSON 对象");
                    closeReason = "frame_invalid";
                    return;
                }
                if (request.projectKey() == null || request.deviceKey() == null || request.secret() == null) {
                    writeError(output, "AUTH_REQUIRED", "AUTH_REQUEST 必须携带项目、设备与密钥");
                    closeReason = "auth_required";
                    return;
                }
                // 认证面预算：与 HTTP 同一份窗口与退避。没有这一步，TCP 端可以无限次试凭据——HTTP 有、TCP 没有，
                // 就等于用一个没被限流的协议去打同一个凭据库。
                switch (authBudget.check(socket.getInetAddress().getHostAddress(), request.projectKey(),
                        request.deviceKey())) {
                    case RATE_LIMITED -> {
                        writeError(output, "RATE_LIMITED", "认证请求超出接入预算");
                        closeReason = "rate_limited";
                        return;
                    }
                    case BACKOFF -> {
                        writeError(output, "RATE_LIMITED", "认证失败退避中");
                        closeReason = "rate_limited";
                        return;
                    }
                    case ALLOW -> {
                        // 继续校验凭据。
                    }
                }
                try {
                    identity = authenticator.authenticate(TransportProtocol.TCP, request.projectKey(),
                            request.deviceKey(), request.secret());
                } catch (DeviceAccessDeviceAuthenticationException exception) {
                    authBudget.recordFailure(request.projectKey(), request.deviceKey());
                    writeError(output, exception.reason().errorCode(), exception.reason().message());
                    closeReason = "auth_failed";
                    return;
                } catch (DeviceAccessRateLimitedException exception) {
                    writeError(output, "RATE_LIMITED", "认证或业务预算已耗尽");
                    closeReason = "rate_limited";
                    return;
                }

                DeviceAccessSessionPort.Establishment establishment = sessionPort.establishAuthenticated(
                        new DeviceAccessSessionPort.EstablishmentRequest(identity.tenantId(), identity.projectId(),
                                identity.deviceId(), TransportProtocol.TCP, remoteAddress, ownerInstance,
                                socket.getInetAddress().getHostAddress(), keepAlive.intervalMillis()), identity);
                if (establishment instanceof DeviceAccessSessionPort.Establishment.Rejected rejected) {
                    authBudget.recordFailure(request.projectKey(), request.deviceKey());
                    writeError(output, rejectionCode(rejected.reason()), rejectionMessage(rejected.reason()));
                    closeReason = "plane_rejected";
                    return;
                }
                established = ((DeviceAccessSessionPort.Establishment.Allowed) establishment).established();
                authBudget.recordSuccess(request.projectKey(), request.deviceKey());
                // 认证完成才切到失效窗口：从这里起「超时」才表示心跳失效而不是握手超时。
                socket.setSoTimeout((int) keepAlive.window().toMillis());
                writeFrame(output, DeviceAccessTcpFrameType.AUTH_RESPONSE, authResponse(established));
                // 认证响应先写出再注册：否则推送可能抢在 AUTH_RESPONSE 之前到达，设备会先收到 DOWNLINK。
                connection = new DeviceAccessTcpConnection(identity.tenantId(), identity.projectId(), identity.deviceId(), established.rowId(),
                        established.generation(), established.configVersion(), output);
                sessionRegistry.register(connection);
                acceleratePendingDeliveries(identity);
                frameLoop(reader, connection, identity, established);
            } catch (SessionTakenOverException exception) {
                // 接管方已经写过 ERROR 帧：这里只收敛会话事实，不能重复写。
                closeReason = "session_taken_over";
            } catch (FatalFrameViolationException violation) {
                // 帧级违规的 ERROR 帧已在写出的地方发过一次：这里只关闭会话，绝不重复发帧。
                closeReason = violation.closeReason;
            } catch (InvalidTcpFrameException exception) {
                closeReason = "frame_invalid";
                LOGGER.debug("设备面 TCP 帧非法 remote={} reason={}", remoteAddress, exception.reason());
                // 线格式错误必须回 ERROR(FRAME_INVALID) 再关闭：静默断开会让设备只知道「连不上」。
                writeFrameQuietly(output, "FRAME_INVALID", exception.reason().message());
            } catch (java.net.SocketTimeoutException exception) {
                // 认证完成前超时＝握手超时；此后＝连续 3 个心跳周期没有帧。两者必须可区分。
                closeReason = established == null ? "handshake_timeout" : "heartbeat_timeout";
                LOGGER.debug("设备面 TCP 会话心跳超时 remote={} interval={}s", remoteAddress,
                        keepAlive.interval().toSeconds());
            } catch (java.io.EOFException exception) {
                // 帧中途对端关闭＝线格式不完整。此时已经没有可写的对端，回 ERROR 没有意义，
                // 但会话事实必须与「干净断开」区分开，否则截断在诊断面上等于消失。
                closeReason = "frame_invalid";
                LOGGER.debug("设备面 TCP 帧被截断 remote={}", remoteAddress);
            } catch (IOException exception) {
                LOGGER.debug("设备面 TCP 连接中断 remote={}", remoteAddress, exception);
            }
        } catch (IOException exception) {
            // try-with-resources 会在 catch 之前关闭套接字：因此错误帧必须在内部的 catch 里写。
            LOGGER.debug("设备面 TCP 连接关闭失败 remote={}", remoteAddress, exception);
        } finally {
            if (connection != null) {
                // 先摘掉注册表条目再关句柄：迟到的推送要么找不到会话，要么落到一个已关闭的句柄上。
                sessionRegistry.unregister(connection);
            }
            if (identity != null && established != null) {
                // 会话事实必须随连接结束收敛：留着活跃会话会让调试面显示一个其实已经断开的设备。
                sessionPort.close(identity.tenantId(), identity.projectId(), identity.deviceId(),
                        established.rowId(), ownerInstance, closeReason);
            }
        }
    }

    /**
     * 设备重连即把因离线而未投递的命令从退避提前到当前时刻（§5.3）。
     *
     * <p>这里只缩短等待：交付仍由既有重投状态机与下行链路完成。加速失败（数据库暂时不可用等）**不得**影响已经
     * 建立的会话——退避本来也会到期重投，因此只记日志。</p>
     *
     * @param identity 已认证设备身份
     */
    private void acceleratePendingDeliveries(AuthenticatedDeviceIdentity identity) {
        try {
            int accelerated = redeliveryPort.accelerateOfflinePending(identity.tenantId(), identity.projectId(),
                    identity.deviceId());
            if (accelerated > 0) {
                LOGGER.debug("设备面 TCP 重连补投已提前待发命令 device={} count={}",
                        identity.deviceId(), accelerated);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("设备面 TCP 重连补投加速失败，命令仍按退避重投 device={}", identity.deviceId(), exception);
        }
    }

    /**
     * 认证后的帧循环：心跳应答，属性上报与业务回复走与 HTTP 相同的用例。
     *
     * <p>帧级违规（拆帧失败、方向错误、未知类型）必须「回一帧 {@code ERROR} 再关闭」：错误帧只在这里发一次，
     * 关闭与会话原因由 {@link FatalFrameViolationException} 交给外层，避免同一个故障被写两帧。</p>
     */
    private void frameLoop(DeviceAccessTcpFrameReader reader, DeviceAccessTcpConnection connection,
                           AuthenticatedDeviceIdentity identity, DeviceAccessSessionPort.Established established)
            throws IOException {
        while (true) {
            DeviceAccessTcpFrameCodec.DecodedFrame frame;
            try {
                frame = reader.readFrame();
            } catch (InvalidTcpFrameException exception) {
                writeError(connection, "FRAME_INVALID", exception.reason().message());
                throw new FatalFrameViolationException("frame_invalid");
            }
            if (frame == null) {
                return;
            }
            // 每次收到帧都确认会话仍属于本实例：被新连接接管的旧连接必须立刻停止被当作有效。
            if (!sessionPort.touch(identity.tenantId(), identity.projectId(), identity.deviceId(),
                    established.rowId(), ownerInstance)) {
                writeError(connection, "AUTH_REQUIRED", "会话已失效或被接管，请重新认证");
                throw new SessionTakenOverException();
            }
            switch (frame.type()) {
                case HEARTBEAT -> writeFrame(connection, DeviceAccessTcpFrameType.HEARTBEAT_ACK, new byte[0]);
                case UPLINK -> handleUplink(connection, identity, frame.payload());
                case REPLY -> handleReply(connection, identity, frame.payload());
                case DOWNLINK -> {
                    writeError(connection, "FRAME_INVALID", "DOWNLINK 是平台到设备的方向");
                    throw new FatalFrameViolationException("frame_invalid");
                }
                default -> {
                    writeError(connection, "FRAME_INVALID", "该帧类型在此阶段不受支持");
                    throw new FatalFrameViolationException("frame_invalid");
                }
            }
        }
    }

    /** 属性上报：与 HTTP 同一条受理链路，失败映射为稳定错误码，成功显式应答，连接保持可用。 */
    private void handleUplink(DeviceAccessTcpConnection connection, AuthenticatedDeviceIdentity identity,
                              byte[] payload) throws IOException {
        try {
            // 业务预算按帧扣：TCP 一次连接只认证一次，若只在认证时扣，帧数就不受限了。
            businessBudget.charge(identity.tenantId(), identity.projectId(), identity.deviceId());
            // 空载荷仍是完整业务帧；在构造信封前转为可诊断的业务拒绝，不能裸断开。
            if (payload.length == 0) {
                throw new InvalidUplinkMessageException("属性上报载荷不能为空");
            }
            var acceptance = uplinkIngress.accept(new DeviceAccessUplinkMessage(identity.tenantId(), identity.projectId(),
                    identity.deviceId(), TransportProtocol.TCP, payload, Instant.now(),
                    TraceContext.resolve(TraceContext.current()), identity));
            writeAccepted(connection, DeviceAccessTcpFrameType.UPLINK, acceptance.messageId(), null);
        } catch (InvalidUplinkMessageException exception) {
            writeError(connection, "PAYLOAD_INVALID", "设备报文不符合冻结契约");
        } catch (DeviceAccessIdempotencyConflictException exception) {
            writeError(connection, "IDEMPOTENCY_CONFLICT", "同一 messageId 的载荷与首次受理不一致");
        } catch (DeviceAccessRateLimitedException exception) {
            writeError(connection, "RATE_LIMITED", "设备业务请求超出接入预算");
        } catch (DeviceAccessHandoffUnavailableException exception) {
            // 总线未接管就不能算受理：必须让设备退避重试，而不是静默当作成功。
            writeError(connection, "HANDOFF_UNAVAILABLE", "消息总线未确认接管，请稍后重试");
        }
    }

    /** 命令业务回复：与 HTTP 共用统一入口，终态由命令事实追踪；业务错误保持连接可用。 */
    private void handleReply(DeviceAccessTcpConnection connection, AuthenticatedDeviceIdentity identity,
                             byte[] payload) throws IOException {
        try {
            businessBudget.charge(identity.tenantId(), identity.projectId(), identity.deviceId());
        } catch (DeviceAccessRateLimitedException exception) {
            writeError(connection, "RATE_LIMITED", "设备业务请求超出接入预算");
            return;
        }
        DeviceAccessAccessPayloads.ReplyRequest parsed;
        try {
            parsed = objectMapper.readValue(payload, DeviceAccessAccessPayloads.ReplyRequest.class);
        } catch (RuntimeException exception) {
            writeError(connection, "PAYLOAD_INVALID", "命令回复不符合冻结契约");
            return;
        }
        DeviceCommandAccessReplyPort.Reply reply;
        try {
            // 与 HTTP／CoAP 共用同一份装配与校验，三个平面不可能校验出不同结果。
            reply = DeviceAccessAccessPayloads.createReply(identity, parsed, Instant.now(),
                    TraceContext.resolve(TraceContext.current()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            writeError(connection, "PAYLOAD_INVALID", "命令回复不符合冻结契约");
            return;
        }
        DeviceCommandAccessReplyPort.Outcome outcome = commandReply.apply(reply);
        switch (outcome) {
            case NOT_FOUND -> writeError(connection, "COMMAND_NOT_FOUND", "命令不存在或不属于该设备");
            case CONFLICT -> writeError(connection, "IDEMPOTENCY_CONFLICT",
                    "同一 messageId 的回复结果与首次不一致");
            // 应用事务已完成；合法重放与首次受理返回同一应答。
            case APPLIED, DUPLICATE -> writeAccepted(connection, DeviceAccessTcpFrameType.REPLY,
                    reply.messageId(), reply.commandId());
        }
    }

    /** 受理后才通过连接写锁确认；省略时间与重放标记，保证同一请求应答稳定。 */
    private void writeAccepted(DeviceAccessTcpConnection connection, DeviceAccessTcpFrameType requestType,
                               UUID messageId, UUID commandId) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestType", requestType.name());
        payload.put("messageId", messageId.toString());
        payload.put("status", "ACCEPTED");
        if (commandId != null) {
            payload.put("commandId", commandId.toString());
        }
        writeFrame(connection, DeviceAccessTcpFrameType.ACCEPTED, objectMapper.writeValueAsBytes(payload));
    }

    /** 认证响应载荷：状态、平台时间、心跳周期与帧上限。 */
    private byte[] authResponse(DeviceAccessSessionPort.Established established) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "OK");
        payload.put("serverTime", Instant.now().toString());
        payload.put("heartbeatIntervalMillis", keepAlive.intervalMillis());
        payload.put("maxFrameBytes", DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES);
        payload.put("generation", established.generation());
        payload.put("configVersion", established.configVersion());
        return objectMapper.writeValueAsBytes(payload);
    }

    /** 尽力回一帧错误：连接可能已断，写失败只记 debug，不再抛出以免覆盖原始原因。 */
    private void writeFrameQuietly(OutputStream output, String errorCode, String message) {
        try {
            writeError(output, errorCode, message);
        } catch (RuntimeException exception) {
            // 连接可能在对端读到之前就断了；错误帧尽力送达，不再抛出以免覆盖原始拒绝原因。
            LOGGER.debug("设备面 TCP 错误帧未能送达", exception);
        }
    }

    /** 错误帧：只有稳定错误码与说明。 */
    private void writeError(OutputStream output, String errorCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        writeFrame(output, DeviceAccessTcpFrameType.ERROR, objectMapper.writeValueAsBytes(payload));
    }

    /** 认证后写一帧；写失败必须终止本连接，不能继续把会话当作可用。 */
    private void writeFrame(DeviceAccessTcpConnection connection, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        if (!connection.writeFrame(type, payload)) {
            throw new IOException("设备面 TCP 写帧失败");
        }
    }

    /** 认证后回一帧错误；与写帧同一条串行化路径。 */
    private void writeError(DeviceAccessTcpConnection connection, String errorCode, String message)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        writeFrame(connection, DeviceAccessTcpFrameType.ERROR, objectMapper.writeValueAsBytes(payload));
    }

    /** 写一帧；写失败说明连接已断，交由上层收尾。 */
    private static void writeFrame(OutputStream output, DeviceAccessTcpFrameType type, byte[] payload) {
        try {
            output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
            output.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("设备面 TCP 写帧失败", exception);
        }
    }

    /** 平面拒绝映射到冻结合同的错误码。 */
    private static String rejectionCode(DeviceAccessSessionPort.Rejection reason) {
        return switch (reason) {
            case PROTOCOL_MISMATCH, DISABLED, MQTT_OWNED_BY_BROKER -> DeviceAccessAuthFailureReason
                    .PLANE_NOT_ENABLED.errorCode();
            case DEVICE_NOT_FOUND, CREDENTIAL_CHANGED -> DeviceAccessAuthFailureReason.AUTH_FAILED.errorCode();
            case PROJECT_UNAVAILABLE -> DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE.errorCode();
        };
    }

    /** 平面拒绝对应的设备侧说明。 */
    private static String rejectionMessage(DeviceAccessSessionPort.Rejection reason) {
        return switch (reason) {
            case PROTOCOL_MISMATCH, DISABLED, MQTT_OWNED_BY_BROKER -> DeviceAccessAuthFailureReason
                    .PLANE_NOT_ENABLED.message();
            case DEVICE_NOT_FOUND, CREDENTIAL_CHANGED -> DeviceAccessAuthFailureReason.AUTH_FAILED.message();
            case PROJECT_UNAVAILABLE -> DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE.message();
        };
    }

    /** 会话已被新连接接管：本连接必须停止服务但不重复回错误帧。 */
    private static final class SessionTakenOverException extends RuntimeException {
    }

    /**
     * 帧级违规：{@code ERROR} 帧已在违规处发出，本连接必须随之关闭。
     *
     * <p>与业务错误分开：业务错误（载荷不合法、幂等冲突、命令不存在、预算耗尽、交接不可用）回一帧
     * {@code ERROR} 后**保持连接**，让设备在同一条连接上修正或退避——否则一次载荷笔误就会让设备重连，
     * 而重连要重新扣认证窗口（§6 每 IP 30 次/分钟），同一 NAT 后的健康设备会被连带挡在门外。</p>
     */
    private static final class FatalFrameViolationException extends RuntimeException {

        /** 写入会话事实的稳定断开原因。 */
        private final String closeReason;

        /**
         * @param closeReason 稳定断开原因
         */
        private FatalFrameViolationException(String closeReason) {
            this.closeReason = closeReason;
        }
    }

    /**
     * AUTH_REQUEST 载荷。
     *
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param secret 设备密钥明文
     */
    record AuthRequest(String projectKey, String deviceKey, String secret) {
    }
}
