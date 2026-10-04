package com.things.link.telemetry.application;

import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository;
import com.things.link.telemetry.domain.PropertyErrorCode;
import com.things.link.telemetry.domain.PropertyPoint;
import com.things.link.telemetry.domain.PropertyPointRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 属性数据摄入与历史查询服务。
 *
 * <p>写入口接收 ingestion 模块已经标准化的消息信封，不接受控制台身份直接写入。
 * messageId 去重、时序落库和影子 CAS 位于同一事务，保证重试不会留下半完成状态。</p>
 */
@Service
public class PropertyIngestionService {
    /** 配额权威源短暂失败只告警并保留完整遥测，不能把计量故障扩大为数据丢失。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyIngestionService.class);
    /** 时序点仓储。 */
    private final PropertyPointRepository repository;
    /** 跨模块公开的项目授权服务。 */
    private final ProjectService projectService;
    /** 可信设备所属租户的历史窗口。 */
    private final PlanCapacityService planCapacityService;
    /** JDBC 用于执行摄入热路径上的原子 SQL。 */
    private final JdbcTemplate jdbc;
    /** S12-2a1b 集中保证事务绑定连接上的完整租户与项目 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** JSON 序列化器，仅负责消息日志载荷摘要。 */
    private final ObjectMapper objectMapper;
    /** device 域公开的数据面端口，隔离 telemetry 对 {@code dev_*} 表的访问。 */
    private final DeviceIngestionService deviceIngestionService;
    /** 与属性事务共同提交的设备消息日志服务。 */
    private final MessageLogService messageLogService;
    /** 数据面指标门面，用事务提交后的时刻定义“影子可见”。 */
    private final DataPlaneMetrics metrics;
    /** S6 告警公开评估端口；telemetry 不得直接写 alarm_* 表。 */
    private final AlarmEvaluationService alarmEvaluationService;
    /** S7-5 时序写入结果指标；固定成功/失败标签，不携带租户或设备 ID。 */
    private final TimeSeriesWriteMetrics timeSeriesWriteMetrics;
    /** PostgreSQL 权威 UTC 日额度，严重超额时只停历史点、保留影子与告警事实。 */
    private final ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    /** ADR0065 要求首次摄入在原事务持续持有项目许可，额度快照不能保护并发归档。 */
    private final ProjectLifecycleAccessService projectLifecycleAccessService;
    /** D-042 把七天常规刷新窗之外的数值迟到点登记为持久回补请求。 */
    private final PropertyAggregateBackfillRepository aggregateBackfillRepository;
    /** 合格属性与受控事件共用原事务，发布器网络交付在事务之外。 */
    private final TransactionalOutboxRepository outboxRepository;

    /**
     * 创建属性服务。
     *
     * @param repository 时序点仓储
     * @param projectService 项目授权服务
     * @param jdbc JDBC 访问入口
     * @param transactionLocalRlsScope 事务局部 RLS 完整范围组件
     * @param objectMapper JSON 序列化器
     * @param deviceIngestionService 设备归属、物模型校验与影子 CAS 端口
     * @param messageLogService 与属性写入共用事务的消息日志服务
     * @param metrics 数据面指标门面
     * @param alarmEvaluationService 告警规则评估端口
     * @param timeSeriesWriteMetrics 时序写入结果指标
     * @param dailyQuotaDecisionService PostgreSQL 权威 UTC 日额度裁决
     * @param projectLifecycleAccessService 首次摄入持有至原事务结束的项目写许可
     * @param aggregateBackfillRepository 连续聚合迟到窗口回补端口
     * @param outboxRepository 原事务可靠事件端口
     */
    public PropertyIngestionService(PropertyPointRepository repository, ProjectService projectService,
                                    JdbcTemplate jdbc, TransactionLocalRlsScope transactionLocalRlsScope,
                                    ObjectMapper objectMapper,
                                    DeviceIngestionService deviceIngestionService,
                                    MessageLogService messageLogService,
                                    DataPlaneMetrics metrics,
                                    AlarmEvaluationService alarmEvaluationService,
                                    TimeSeriesWriteMetrics timeSeriesWriteMetrics,
                                    ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                                    ProjectLifecycleAccessService projectLifecycleAccessService,
                                    PropertyAggregateBackfillRepository aggregateBackfillRepository,
                                    TransactionalOutboxRepository outboxRepository, PlanCapacityService planCapacityService) {
        this.repository = repository;
        this.projectService = projectService;
        this.planCapacityService = planCapacityService;
        this.jdbc = jdbc;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.objectMapper = objectMapper;
        this.deviceIngestionService = deviceIngestionService;
        this.messageLogService = messageLogService;
        this.metrics = metrics;
        this.alarmEvaluationService = alarmEvaluationService;
        this.timeSeriesWriteMetrics = timeSeriesWriteMetrics;
        this.dailyQuotaDecisionService = dailyQuotaDecisionService;
        this.projectLifecycleAccessService = projectLifecycleAccessService;
        this.aggregateBackfillRepository = aggregateBackfillRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 查询设备属性历史。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKey 可选属性标识符
     * @param from 可选起始时刻
     * @param to 可选结束时刻
     * @param cursor 可选游标
     * @param limit 每页数量
     * @return 一页历史点
     */
    public CursorPage<PropertyPoint> listProperties(UUID projectId, UUID deviceId, String propertyKey,
                                                     Instant from, Instant to, String cursor, int limit) {
        projectService.requireRoleInProject(projectId);
        var owner = deviceIngestionService.requireDeviceOwner(projectId, deviceId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "开始时间必须早于结束时间");
        }
        var window = planCapacityService.historyWindow(owner.tenantId(), projectId);
        // 空交集仍交仓储验证游标；矛盾区间在SQL中自然返回空，不放宽窗口。
        return repository.findByDevice(projectId, deviceId, propertyKey,
                window.clipFrom(from), window.clipTo(to), cursor, limit);
    }

    /**
     * 摄入一条标准化属性消息。
     *
     * @param message 标准化消息
     * @return true 表示首次处理；false 表示相同 messageId 已成功处理
     */
    @Transactional
    public boolean ingest(PropertyReportMessage message) {
        validateMessageEnvelope(message);
        Map<String, Object> properties = Collections.singletonMap(message.propertyKey(), message.value());
        DeviceIngestionService.DeviceOwnerContext device = deviceIngestionService.validateReportedProperties(
                message.projectId(), message.deviceId(), properties);

        // inbox 记录和业务写入同事务提交；后续任一步失败时记录也会回滚，Kafka 可以安全重试。
        int acquired = jdbc.update("""
                INSERT INTO sys_inbox_message (message_id, project_id, received_at)
                VALUES (?, ?, now())
                ON CONFLICT (message_id) DO NOTHING
                """, message.messageId(), message.projectId());
        if (acquired == 0) {
            return false;
        }

        PropertyPoint point = buildPoint(message);
        savePoint(point);
        // 旧内部单属性端口没有版本资格，只保留存量测试历史，禁止伪造版本推进影子。
        recordUplinkVisibleAfterCommit(message.occurredAt());
        return true;
    }

    /**
     * 在一个项目事务中摄入标准信封携带的全部属性。
     *
     * <p>批量属性共享一个 messageId，因此只能抢占一次 inbox。所有属性先完成物模型校验，之后才登记
     * inbox 和写表；任一属性非法都会让整条设备报文失败，禁止留下部分时序点或半更新影子。</p>
     * <p>ADR0065 允许仲裁先于项目许可，但首次仲裁只有在原事务持续持有写许可后才能提交；
     * 拒绝会回滚 inbox，已有且仍通过原版本校验的精确重放继续返回 false。</p>
     *
     * @param message ingestion 已完成传输协议解析的标准上行信封
     * @return true 表示首次完整处理，false 表示同一 messageId 已成功处理
     * @throws ProjectIngestionRejectedException 首次摄入未取得项目写许可时整体回滚
     */
    @Transactional
    public boolean ingest(StandardUplinkMessage message) {
        validateStandardEnvelope(message);
        // 标准信封身份已经由接入链路确权；集中组件只负责把完整二元组绑定到当前真实事务连接。
        transactionLocalRlsScope.establish(message.tenantId(), message.projectId());
        DeviceIngestionContext device = deviceIngestionService.validateReportedProperties(
                message.tenantId(), message.projectId(), message.deviceId(), message.modelVersion(), message.receivedAt(),
                message.payload());
        if (!message.tenantId().equals(device.tenantId())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "标准消息租户归属不一致");
        }

        if (!tryAcquireMessage(message, device)) {
            return false;
        }
        // ADR0065：inbox 只是同事务仲裁；拒绝必须连它一起回滚，不能进入额度 catch 或返回正常幂等。
        // 保留已成功消息的原 false 路径；首次写的 SHARE 必须早于不可回滚的 legacy 指标及后续业务事实。
        if (!projectLifecycleAccessService.lockActiveForWrite(device.tenantId(), message.projectId())) {
            throw new ProjectIngestionRejectedException();
        }
        if (device.legacyInferred()) {
            metrics.recordLegacyModelVersionInferred();
        }

        boolean degraded = isHistoricalStorageDegraded(message);
        if (!degraded) {
            for (Map.Entry<String, Object> property : message.payload().entrySet()) {
                savePoint(buildPoint(message, property, device));
            }
            if (message.payload().values().stream().anyMatch(Number.class::isInstance)) {
                // 请求与 raw 点同事务提交；回滚时不能留下一个指向不存在事实的回补窗口。
                aggregateBackfillRepository.request(message.tenantId(), message.projectId(),
                        message.occurredAt(), message.receivedAt());
            }
        }
        if (device.eligibility() == DeviceIngestionContext.Eligibility.CURRENT) {
            deviceIngestionService.mergeReportedProperties(
                    device, message.projectId(), message.deviceId(), message.payload(), message.occurredAt(),
                    message.messageId(), message.traceId());
            evaluateNumericAlarms(message);
            appendAutomationProperty(message, device);
        }

        String payloadSummary = objectMapper.writeValueAsString(message.payload());
        messageLogService.log(new DeviceMessageLogCommand(
                message.projectId(), message.deviceId(), message.messageId(),
                message.protocol(),
                DeviceMessageLog.Direction.valueOf(message.direction().name()),
                null, payloadSummary, message.rawBytes(), null,
                message.occurredAt(), message.receivedAt(), message.traceId(), "PROPERTY_REPORT"));
        if (device.eligibility() == DeviceIngestionContext.Eligibility.CURRENT) {
            recordUplinkVisibleAfterCommit(message.occurredAt());
        }
        return true;
    }

    /** 只有首次CURRENT摄入可到达；后续消息日志或提交失败会连事件一起回滚。 */
    private void appendAutomationProperty(StandardUplinkMessage message, DeviceIngestionContext device) {
        Instant acceptedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        AutomationPropertyAccepted accepted = new AutomationPropertyAccepted(1, message.messageId(),
                message.tenantId(), message.projectId(), message.deviceId(), device.versionNumber(),
                message.occurredAt(), acceptedAt, message.payload(), message.traceId());
        outboxRepository.append(new OutboxEvent(Uuid7.generate(), message.tenantId(), message.projectId(),
                AutomationPropertyAccepted.AGGREGATE_TYPE, message.deviceId(), AutomationPropertyAccepted.EVENT_TYPE,
                message.deviceId().toString(), objectMapper.writeValueAsString(accepted), message.traceId(), acceptedAt));
    }

    /** 上行条数、原始字节或时序点明确禁用或严重超额时停止新增历史点，保留其余事务事实。 */
    private boolean isHistoricalStorageDegraded(StandardUplinkMessage message) {
        try {
            return java.util.stream.Stream.of(
                            QuotaMetric.UPLINK_MESSAGE, QuotaMetric.UPLINK_BYTES, QuotaMetric.TIME_SERIES_POINT)
                    .map(metric -> dailyQuotaDecisionService.decisionTrustedProject(
                            message.tenantId(), message.projectId(), metric))
                    .anyMatch(decision -> decision.disabled() || decision.status() == QuotaStatus.DEGRADED);
        } catch (RuntimeException exception) {
            LOGGER.warn("遥测日额度无法从 PostgreSQL 权威事实判定，本次保留完整历史写入", exception);
            return false;
        }
    }

    /**
     * 仅把已经过物模型校验的数值属性交给告警域。
     *
     * <p>这一调用仍位于 inbox、时序点、影子和消息日志的同一事务内；告警域只能写自己的
     * {@code alarm_*} 表，不能反向让 telemetry 知道规则 SQL。文本与布尔属性不属于 S6-1 固定阈值能力，
     * 后续规则域扩展时必须新增明确的类型契约，不能把它们转成字符串再隐式比较。</p>
     */
    private void evaluateNumericAlarms(StandardUplinkMessage message) {
        for (Map.Entry<String, Object> property : message.payload().entrySet()) {
            if (property.getValue() instanceof Number number) {
                alarmEvaluationService.evaluate(new AlarmEvaluationInput(message.messageId(), message.tenantId(),
                        message.projectId(), message.deviceId(), property.getKey(), number.doubleValue(),
                        message.occurredAt(), message.receivedAt(), message.traceId()));
            }
        }
    }

    /**
     * 只在当前事务真正提交后记录延迟，回滚或尚未提交的影子不能被称作“可见”。
     *
     * @param occurredAt 设备声明的发生时间
     */
    private void recordUplinkVisibleAfterCommit(Instant occurredAt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 数据库提交完成后影子才对其他事务可见。 */
            @Override
            public void afterCommit() {
                metrics.recordUplinkVisible(occurredAt);
            }
        });
    }

    /** 校验本应用端口当前支持的标准消息分类和必填归属。 */
    private static void validateStandardEnvelope(StandardUplinkMessage message) {
        if (message == null || message.tenantId() == null || message.projectId() == null
                || message.deviceId() == null || message.type() != StandardUplinkMessage.Type.PROPERTY_REPORT
                || message.direction() != StandardUplinkMessage.Direction.UP
                || message.payload() == null || message.payload().isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "标准属性消息信封不完整");
        }
    }

    /** 将批量信封中的一个属性映射为既有内部单属性契约。 */
    private static PropertyReportMessage propertyMessage(
            StandardUplinkMessage message, Map.Entry<String, Object> entry) {
        return new PropertyReportMessage(message.messageId(), message.projectId(), message.deviceId(),
                entry.getKey(), entry.getValue(), message.occurredAt());
    }

    /**
     * 以整条标准消息为粒度抢占 inbox，并拒绝同 ID 换版本或换载荷。
     *
     * <p>摘要由 PostgreSQL {@code jsonb::text} 计算，与 B-X1a 的
     * {@code PG_JSONB_TEXT_V1_SHA256} 规范化语义一致，不受对象键顺序或空白影响。</p>
     */
    private boolean tryAcquireMessage(StandardUplinkMessage message, DeviceIngestionContext context) {
        String payloadJson = objectMapper.writeValueAsString(message.payload());
        int inserted = jdbc.update("""
                INSERT INTO sys_inbox_message
                    (message_id, project_id, received_at, thing_model_version_id, payload_digest)
                VALUES (?, ?, ?, ?, encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'))
                ON CONFLICT (message_id) DO NOTHING
                """, message.messageId(), message.projectId(), Timestamp.from(message.receivedAt()),
                context.thingModelVersionId(), payloadJson);
        if (inserted == 1) {
            return true;
        }
        Boolean identical = jdbc.queryForObject("""
                SELECT project_id = ?
                   AND thing_model_version_id = ?
                   AND payload_digest = encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex')
                  FROM sys_inbox_message
                 WHERE message_id = ?
                """, Boolean.class, message.projectId(), context.thingModelVersionId(), payloadJson,
                message.messageId());
        if (!Boolean.TRUE.equals(identical)) {
            throw new BusinessException(PropertyErrorCode.UPLINK_REPLAY_CONFLICT);
        }
        return false;
    }

    /** 校验标准消息信封的必填字段。 */
    private static void validateMessageEnvelope(PropertyReportMessage message) {
        if (message == null || message.messageId() == null || message.projectId() == null
                || message.deviceId() == null || message.occurredAt() == null
                || message.propertyKey() == null || !message.propertyKey().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "属性上报消息信封不完整");
        }
    }

    /** 把单值放入且仅放入一个类型列。 */
    private PropertyPoint buildPoint(PropertyReportMessage message) {
        Object value = message.value();
        return switch (value) {
            case Number number -> new PropertyPoint(message.projectId(), message.deviceId(), message.propertyKey(),
                    message.occurredAt(), message.messageId(), null, null, null,
                    number.doubleValue(), null, null, null, (short) 0);
            case Boolean bool -> new PropertyPoint(message.projectId(), message.deviceId(), message.propertyKey(),
                    message.occurredAt(), message.messageId(), null, null, null,
                    null, null, bool, null, (short) 0);
            case String text -> new PropertyPoint(message.projectId(), message.deviceId(), message.propertyKey(),
                    message.occurredAt(), message.messageId(), null, null, null,
                    null, text, null, null, (short) 0);
            default -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "属性值类型不受支持");
        };
    }

    /** 按写入时冻结类型选择唯一值列，复合值始终保持原生 JSON 根类型。 */
    private PropertyPoint buildPoint(StandardUplinkMessage message, Map.Entry<String, Object> property,
                                     DeviceIngestionContext context) {
        String dataType = context.propertyDataTypes().get(property.getKey());
        Object value = property.getValue();
        return switch (dataType) {
            case "NUMBER" -> new PropertyPoint(message.projectId(), message.deviceId(), property.getKey(),
                    message.occurredAt(), message.messageId(), dataType, context.thingModelVersionId(),
                    context.versionNumber(), ((Number) value).doubleValue(), null, null, null, (short) 0);
            case "TEXT", "ENUM" -> new PropertyPoint(message.projectId(), message.deviceId(), property.getKey(),
                    message.occurredAt(), message.messageId(), dataType, context.thingModelVersionId(),
                    context.versionNumber(), null, (String) value, null, null, (short) 0);
            case "SWITCH" -> new PropertyPoint(message.projectId(), message.deviceId(), property.getKey(),
                    message.occurredAt(), message.messageId(), dataType, context.thingModelVersionId(),
                    context.versionNumber(), null, null, (Boolean) value, null, (short) 0);
            case "OBJECT", "LIST" -> new PropertyPoint(message.projectId(), message.deviceId(), property.getKey(),
                    message.occurredAt(), message.messageId(), dataType, context.thingModelVersionId(),
                    context.versionNumber(), null, null, null, objectMapper.writeValueAsString(value), (short) 0);
            default -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "属性数据类型不受支持");
        };
    }

    /**
     * 用固定结果指标包住真实 JDBC 写入；异常继续抛给 Kafka 重试，绝不能为了指标吞掉失败。
     *
     * @param point 已完成物模型校验的属性点
     */
    private void savePoint(PropertyPoint point) {
        try {
            repository.save(point);
            timeSeriesWriteMetrics.recordSuccess();
        } catch (RuntimeException exception) {
            timeSeriesWriteMetrics.recordFailure();
            throw exception;
        }
    }

}
