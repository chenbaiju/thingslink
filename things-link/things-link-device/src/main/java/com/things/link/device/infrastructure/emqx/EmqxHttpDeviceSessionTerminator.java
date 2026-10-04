package com.things.link.device.infrastructure.emqx;

import com.things.link.device.application.DeviceSessionTerminator;
import com.things.link.device.domain.DeviceConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;

/**
 * 通过 EMQX 5 管理 API 踢出凭据撤销/轮换前已建立的 MQTT 会话。
 *
 * <p>每个设备可同时存在多个 clientId，因此严禁从 deviceKey 猜测；只使用
 * {@code dev_connection.session_id} 中尚未关闭的事实。EMQX 不可用时不回滚已提交的撤销，
 * 旧凭据的新连接仍 fail-closed；失败通过固定指标和不含密钥的日志暴露。</p>
 */
@Component
public class EmqxHttpDeviceSessionTerminator implements DeviceSessionTerminator {

    /** EMQX 5 按 clientId 断开连接的固定路径。 */
    private static final String CLIENT_PATH = "/api/v5/clients/{clientId}";
    /** 失败需带业务定位信息，但不得记录 API Secret。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EmqxHttpDeviceSessionTerminator.class);
    /** 活跃会话事实端口。 */
    private final DeviceConnectionRepository connectionRepository;
    /** 首次真实断开时才创建 HTTP 客户端，避免空闲进程占用 selector 资源。 */
    private final Supplier<RestClient> restClientFactory;
    /** 线程安全的惰性客户端。 */
    private volatile RestClient restClient;
    /** 最小权限 EMQX 管理 API Key。 */
    private final String apiKey;
    /** EMQX 管理 API Secret，仅写入 Authorization 头。 */
    private final String apiSecret;
    /** 固定结果指标。 */
    private final DeviceSessionTerminationMetrics metrics;

    /**
     * @param connectionRepository 活跃会话事实端口
     * @param baseUrl EMQX 管理 API 根地址
     * @param apiKey 最小权限 Key
     * @param apiSecret 最小权限 Secret
     * @param connectTimeout 连接建立上限
     * @param readTimeout 读取响应上限
     * @param metrics 固定结果指标
     */
    @Autowired
    public EmqxHttpDeviceSessionTerminator(
            DeviceConnectionRepository connectionRepository,
            @Value("${things-link.device.emqx-session-api.base-url:http://localhost:18083}") String baseUrl,
            @Value("${things-link.device.emqx-session-api.api-key:}") String apiKey,
            @Value("${things-link.device.emqx-session-api.api-secret:}") String apiSecret,
            @Value("${things-link.device.emqx-session-api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${things-link.device.emqx-session-api.read-timeout:5s}") Duration readTimeout,
            DeviceSessionTerminationMetrics metrics) {
        this(connectionRepository,
                () -> RestClient.builder()
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .requestFactory(requestFactory(connectTimeout, readTimeout))
                        .build(),
                apiKey, apiSecret, metrics);
    }

    /** 测试构造器允许注入绑定 MockRestServiceServer 的惰性客户端。 */
    EmqxHttpDeviceSessionTerminator(DeviceConnectionRepository connectionRepository,
                                    Supplier<RestClient> restClientFactory,
                                    String apiKey, String apiSecret,
                                    DeviceSessionTerminationMetrics metrics) {
        this.connectionRepository = connectionRepository;
        this.restClientFactory = restClientFactory;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.apiSecret = apiSecret == null ? "" : apiSecret.strip();
        this.metrics = metrics;
    }

    /** {@inheritDoc} */
    @Override
    public void disconnectAfterCommit(UUID projectId, UUID deviceId) {
        // 在控制事务关闭事实前捕获；提交回调只消费快照，不能重新查活跃行。
        List<String> sessionIds = capture(projectId, deviceId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            disconnect(projectId, deviceId, sessionIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** @param status 只有凭据事实已提交才允许踢出会话 */
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    disconnect(projectId, deviceId, sessionIds);
                }
            }
        });
    }

    /** 在原事务中读取不可变待踢列表，读取故障仍可观测。 */
    private List<String> capture(UUID projectId, UUID deviceId) {
        try {
            return List.copyOf(connectionRepository.findActiveMqttSessionIds(projectId, deviceId));
        } catch (RuntimeException exception) {
            metrics.record(DeviceSessionTerminationMetrics.Result.FAILED);
            LOGGER.error("EMQX 会话断开无法读取活跃会话 projectId={} deviceId={}",
                    projectId, deviceId, exception);
            return List.of();
        }
    }

    /** 逐个断开捕获的会话；单个clientId失败不阻断其他会话收敛。 */
    private void disconnect(UUID projectId, UUID deviceId, List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        if (apiKey.isEmpty() || apiSecret.isEmpty()) {
            metrics.record(DeviceSessionTerminationMetrics.Result.CREDENTIALS_MISSING);
            LOGGER.error("EMQX 会话断开凭据缺失 projectId={} deviceId={} activeSessions={}",
                    projectId, deviceId, sessionIds.size());
            return;
        }
        for (String sessionId : sessionIds) {
            disconnectOne(projectId, deviceId, sessionId);
        }
    }

    /** 断开单个 clientId；404 表示回调与管理 API 竞态中会话已不存在，按幂等成功收敛。 */
    private void disconnectOne(UUID projectId, UUID deviceId, String sessionId) {
        try {
            HttpStatusCode status = client().delete()
                    .uri(CLIENT_PATH, sessionId)
                    .headers(headers -> headers.setBasicAuth(apiKey, apiSecret))
                    .exchange((request, response) -> response.getStatusCode());
            if (status.value() == 404) {
                metrics.record(DeviceSessionTerminationMetrics.Result.ALREADY_ABSENT);
            } else if (status.is2xxSuccessful()) {
                metrics.record(DeviceSessionTerminationMetrics.Result.DISCONNECTED);
            } else {
                metrics.record(DeviceSessionTerminationMetrics.Result.FAILED);
                LOGGER.error("EMQX 拒绝会话断开 projectId={} deviceId={} clientId={} status={}",
                        projectId, deviceId, sessionId, status.value());
            }
        } catch (RuntimeException exception) {
            metrics.record(DeviceSessionTerminationMetrics.Result.FAILED);
            LOGGER.error("EMQX 会话断开失败 projectId={} deviceId={} clientId={}",
                    projectId, deviceId, sessionId, exception);
        }
    }

    /** @return 线程安全的惰性 HTTP 客户端 */
    private RestClient client() {
        RestClient current = restClient;
        if (current == null) {
            synchronized (this) {
                current = restClient;
                if (current == null) {
                    current = restClientFactory.get();
                    restClient = current;
                }
            }
        }
        return current;
    }

    /** @return 强制 HTTP/1.1 且有连接/读取上限的请求工厂 */
    private static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** @return 去掉尾部斜线的管理 API 根地址 */
    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
