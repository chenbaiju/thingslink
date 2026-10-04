package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.application.DeviceAccessScopeService.ResolvedDeviceAccessScope;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.trace.TraceContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将 EMQX 已认证发布事件转换为 Kafka 原始上行信封。
 *
 * <p>本用例只确权与封装，不解析设备 JSON。HTTP 成功前等待 Kafka broker 确认，发送失败必须向上冒泡并显式暴露故障。
 * 但 G1-C3d #5 已证明 EMQX 5.8.4 会把有效 HTTP 500 判为不可恢复，抛异常本身不等于至少一次重试；端到端 durable
 * handoff 的后续设计登记在架构缺口 G-15，不能由本服务注释虚构保证。</p>
 */
@Service
public class RawUplinkIngestionService {

    /** 原始上行主题，12 分区由部署初始化显式创建，禁止依赖 broker 自动建主题。 */
    public static final String RAW_UPLINK_TOPIC = "tc.device.uplink.raw";
    /** Broker 回执等待上限，小于 EMQX Webhook 请求超时，给 HTTP 响应保留传播时间。 */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(2);
    /** 设备域公开的可信身份解析契约。 */
    private final DeviceAccessScopeService scopeService;
    /** Kafka 生产模板，由 S3-11B 配置幂等与 trace header。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** 设备桶与租户共享短窗口限流器，在 Kafka 前丢弃超限消息以保护整条数据面。 */
    private final DeviceUplinkRateLimiter rateLimiter;
    /** project 模块公开策略端口；只按已确权项目派生所有者租户，避免协作者 JWT 租户误用。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** 策略无法解析时必须留下低基数安全默认指标，避免降级静默发生。 */
    private final QuotaRuntimeMetrics quotaMetrics;

    /**
     * 创建原始消息摄入用例。
     *
     * @param scopeService 设备接入范围服务
     * @param kafkaTemplate Kafka 模板
     * @param rateLimiter 设备与租户共享上行短窗口限流器
     * @param quotaPolicyProvider 有效租户策略公开端口
     * @param quotaMetrics 配额缓存与限流低基数指标
     */
    public RawUplinkIngestionService(DeviceAccessScopeService scopeService,
                                     KafkaTemplate<String, Object> kafkaTemplate,
                                     DeviceUplinkRateLimiter rateLimiter,
                                     EffectiveQuotaPolicyProvider quotaPolicyProvider,
                                     QuotaRuntimeMetrics quotaMetrics) {
        this.scopeService = scopeService;
        this.kafkaTemplate = kafkaTemplate;
        this.rateLimiter = rateLimiter;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.quotaMetrics = quotaMetrics;
    }

    /**
     * 校验接入元数据并发布原始信封。
     *
     * @param request EMQX 发布事件
     * @return 合法消息是否已由 Kafka broker 确认
     */
    public boolean ingest(EmqxMessagePublishedRequest request) {
        return ingestHandoff(request) == HandoffDisposition.ACCEPTED;
    }

    /**
     * 为 manual-ACK ingress 返回稳定结果；依赖故障继续抛出，禁止误归为永久拒绝。
     *
     * @param request Broker 持久交接信封还原出的发布事件
     * @return 可确认的接纳或永久拒绝分类
     */
    public HandoffDisposition ingestHandoff(EmqxMessagePublishedRequest request) {
        return ingestHandoff(request, null);
    }

    /** 内部Broker专用身份入口；只保存签发时版本，不查询当前版本来升级旧消息。 */
    public HandoffDisposition ingestHandoff(EmqxMessagePublishedRequest request, AuthenticatedDeviceIdentity identity) {
        Optional<MqttUplinkTopic> topic = MqttUplinkTopic.parse(request.topic());
        if (topic.isEmpty() || !topic.orElseThrow().belongsTo(request.username())
                || request.qos() != 1 || request.retained()) {
            return HandoffDisposition.PERMANENT_REJECT;
        }
        byte[] payload = decodePayload(request.payloadBase64());
        if (payload.length == 0 || request.publishedAtMs() < 0) {
            return HandoffDisposition.PERMANENT_REJECT;
        }
        Optional<ResolvedDeviceAccessScope> scope = scopeService.resolve(
                topic.orElseThrow().projectKey(), topic.orElseThrow().deviceKey());
        if (scope.isEmpty()) {
            return HandoffDisposition.PERMANENT_REJECT;
        }

        String traceId = TraceContext.resolve(TraceContext.current());
        ResolvedDeviceAccessScope resolved = scope.orElseThrow();
        if (identity != null && (!identity.tenantId().equals(resolved.tenantId())
                || !identity.projectId().equals(resolved.projectId()) || !identity.deviceId().equals(resolved.deviceId()))) {
            return HandoffDisposition.PERMANENT_REJECT;
        }
        // HTTP 仍返回 200，由 accepted=false 表达本条未入 Kafka；不能抛 429 触发 EMQX 重试并放大洪峰。
        EffectiveQuotaPolicy policy;
        try {
            policy = quotaPolicyProvider.resolveTrustedDeviceProject(resolved.tenantId(), resolved.projectId());
        } catch (IllegalArgumentException exception) {
            // 受限投影用 IllegalArgumentException 表达 tenant/project 不匹配或项目已失效；这属于确权失败，不能按依赖故障放行。
            return HandoffDisposition.PERMANENT_REJECT;
        } catch (RuntimeException exception) {
            // 项目已经由设备确权服务解析，故障时仅使用该可信归属构造有限默认，不把策略缺失扩大为遥测中断。
            policy = EffectiveQuotaPolicy.safeDefault(resolved.tenantId());
            quotaMetrics.recordCache(QuotaRuntimeMetrics.CacheResult.SAFE_DEFAULT);
        }
        if (!rateLimiter.tryAcquire(resolved.tenantId(), resolved.deviceId(), policy)) {
            return HandoffDisposition.PERMANENT_REJECT;
        }
        RawUplinkMessage message = new RawUplinkMessage(resolved.tenantId(), resolved.projectId(),
                resolved.deviceId(), request.topic(), payload, request.qos(), request.retained(),
                request.clientId(), Instant.ofEpochMilli(request.publishedAtMs()), traceId, identity);
        long deadlineNanos = System.nanoTime() + SEND_TIMEOUT.toNanos();
        try {
            // KafkaTemplate.send 本身可为元数据同步阻塞；application.yml 同时把 max.block.ms 锁到 1 秒，
            // 这里再从统一截止时间扣除 send 已消耗时长，不能把“Future 等 2 秒”误写成“总耗时 2 秒”。
            var delivery = kafkaTemplate.send(RAW_UPLINK_TOPIC, message.partitionKey().toString(), message);
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                delivery.cancel(true);
                throw new TimeoutException("Kafka send 在返回 Future 前已耗尽原始上行等待预算");
            }
            delivery.get(remainingNanos, TimeUnit.NANOSECONDS);
            return HandoffDisposition.ACCEPTED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Kafka 原始上行消息确认时被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 原始上行消息发送失败", exception);
        }
    }

    /** Base64 非法时按不可信回调拒绝，不把原始内容写入日志。 */
    private static byte[] decodePayload(String payloadBase64) {
        try {
            return Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

}
