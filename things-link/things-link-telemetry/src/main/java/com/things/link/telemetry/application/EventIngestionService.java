package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceEventIngestionContext;
import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.EventErrorCode;
import com.things.link.telemetry.domain.PropertyErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 事件的Inbox、额度、发生事实及脱敏日志共同提交；CURRENT首次受理同步更新设备上报活跃投影，不驱动影子或告警。 */
@Service
public class EventIngestionService {
    /** 同一原事务的数据库访问器。 */ private final JdbcTemplate jdbc;
    /** 可信二元范围只能在事务绑定连接建立。 */ private final TransactionLocalRlsScope rls;
    /** 不可变模型与绑定资格的设备公开端口。 */ private final DeviceIngestionService devices;
    /** 首次写持续持有项目共享许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 当前权威上行条数、字节裁决，异常不得放行。 */ private final ProjectDailyQuotaDecisionService quota;
    /** 原事务日志端口。 */ private final MessageLogService logs;
    /** 与日志相同的凭据整字段移除规则。 */ private final MessageLogRedactor redactor;
    /** JSON序列化只供数据库语义摘要与脱敏，不日志输出。 */ private final ObjectMapper json;

    /**
     * @param jdbc 原事务数据库访问器
     * @param rls 可信事务范围组件
     * @param devices 设备事件快照校验端口
     * @param lifecycle 项目写许可
     * @param quota 权威日额度
     * @param logs 原事务消息日志
     * @param redactor 凭据脱敏器
     * @param json JSON映射器
     */
    public EventIngestionService(JdbcTemplate jdbc, TransactionLocalRlsScope rls,
                                 DeviceIngestionService devices, ProjectLifecycleAccessService lifecycle,
                                 ProjectDailyQuotaDecisionService quota, MessageLogService logs,
                                 MessageLogRedactor redactor, ObjectMapper json) {
        this.jdbc = jdbc; this.rls = rls; this.devices = devices; this.lifecycle = lifecycle;
        this.quota = quota; this.logs = logs;
        this.json = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        // 事件事实独立使用精确解析器执行相同脱敏规则，禁止共享日志解析器把合法十进制舍入后落库。
        this.redactor = new MessageLogRedactor(this.json);
    }

    /**
     * 首次事件按原快照校验并原子登记；已成功精确重放不重新判绑定、写许可或计费。
     * @param message 接入层已确权、严格解析的独立事件信封
     * @return 首次完整受理为true，精确成功重放为false
     */
    @Transactional
    public boolean ingest(EventUplinkMessage message) {
        validateEnvelope(message);
        rls.establish(message.tenantId(), message.projectId());
        String semantic = semantic(message);
        if (existing(message, semantic)) return false;
        DeviceEventIngestionContext context = devices.validateReportedEvent(message.tenantId(),
                message.projectId(), message.deviceId(), message.modelVersion(), message.receivedAt(),
                message.eventKey(), message.params());
        if (!message.tenantId().equals(context.tenantId())
                || !message.modelVersion().equals(context.modelVersion())
                || !message.eventKey().equals(context.eventKey())) {
            throw new BusinessException(EventErrorCode.EVENT_INVALID);
        }
        int acquired;
        try {
            acquired = jdbc.update("""
                INSERT INTO sys_inbox_message
                    (message_id,project_id,received_at,thing_model_version_id,payload_digest,message_kind)
                VALUES (?,?,?,?,encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'EVENT')
                ON CONFLICT (message_id) DO NOTHING
                """, message.messageId(), message.projectId(), Timestamp.from(message.receivedAt()),
                context.thingModelVersionId(), semantic);
        } catch (DataAccessException failure) {
            // 只识别当前Inbox语句中受控触发器的稳定额度约束；事务已失败，转换后仍须完整回滚。
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException postgres && "23514".equals(postgres.getSQLState())
                        && postgres.getServerErrorMessage() != null
                        && "sys_shc_local_message_usage_quota".equals(postgres.getServerErrorMessage().getConstraint())) {
                    throw new BusinessException(EventErrorCode.EVENT_QUOTA_REJECTED);
                }
            }
            throw failure;
        }
        if (acquired == 0) {
            if (existing(message, semantic)) return false;
            throw new BusinessException(PropertyErrorCode.UPLINK_REPLAY_CONFLICT);
        }
        if (!lifecycle.lockActiveForWrite(message.tenantId(), message.projectId())) {
            throw new ProjectIngestionRejectedException();
        }
        for (QuotaMetric metric : List.of(QuotaMetric.UPLINK_MESSAGE, QuotaMetric.UPLINK_BYTES)) {
            var decision = quota.decisionTrustedProject(message.tenantId(), message.projectId(), metric);
            if (decision.disabled() || (decision.status() != QuotaStatus.NORMAL
                    && decision.status() != QuotaStatus.SOFT_LIMIT)) {
                throw new BusinessException(EventErrorCode.EVENT_QUOTA_REJECTED);
            }
        }
        // 完整参数只进入内存语义摘要；schema通过后才脱敏，不能对脱敏投影重验required。
        String params = json.writeValueAsString(message.params());
        String safe = redactor.redact(params);
        boolean redacted = !json.readTree(params).equals(json.readTree(safe));
        jdbc.update("""
                INSERT INTO ts_device_event
                    (message_id,tenant_id,project_id,device_id,device_type_id,thing_model_version_id,
                     model_version,event_key,level,eligibility,occurred_at,received_at,accepted_at,
                     params,params_redacted,input_digest,raw_bytes)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?::jsonb,?,
                        encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?)
                """, message.messageId(), message.tenantId(), message.projectId(), message.deviceId(),
                context.deviceTypeId(), context.thingModelVersionId(), context.modelVersion(),
                context.eventKey(), context.level(), context.eligibility().name(),
                Timestamp.from(message.occurredAt()), Timestamp.from(message.receivedAt()),
                safe, redacted, semantic, message.rawBytes());
        logs.log(new DeviceMessageLogCommand(message.projectId(), message.deviceId(), message.messageId(),
                message.protocol(), DeviceMessageLog.Direction.UP, null, safe, message.rawBytes(), null,
                message.occurredAt(), message.receivedAt(), message.traceId(), "EVENT"));
        if (context.eligibility() == DeviceIngestionContext.Eligibility.CURRENT) {
            devices.recordAcceptedDataReport(message.tenantId(), message.projectId(),
                    message.deviceId(), message.receivedAt());
        }
        return true;
    }

    /** 可见原事实必须语义精确相同；RLS不可见全局碰撞由Inbox唯一仲裁统一拒绝。 */
    private boolean existing(EventUplinkMessage message, String semantic) {
        List<Boolean> rows = jdbc.query("""
                SELECT tenant_id=? AND project_id=? AND device_id=? AND model_version=? AND event_key=?
                   AND occurred_at=? AND input_digest=encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex')
                  FROM ts_device_event WHERE message_id=?
                """, (rs, row) -> rs.getBoolean(1), message.tenantId(), message.projectId(), message.deviceId(),
                message.modelVersion(), message.eventKey(), Timestamp.from(message.occurredAt()), semantic,
                message.messageId());
        if (rows.isEmpty()) return false;
        if (!Boolean.TRUE.equals(rows.getFirst())) throw new BusinessException(PropertyErrorCode.UPLINK_REPLAY_CONFLICT);
        return true;
    }

    /** PostgreSQL jsonb规范文本摘要冻结可信归属、消息种类、原时间及完整参数，重传元数据不参与。 */
    private String semantic(EventUplinkMessage message) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tenantId", message.tenantId().toString());
        content.put("projectId", message.projectId().toString());
        content.put("deviceId", message.deviceId().toString());
        content.put("protocol", "MQTT"); content.put("direction", "UP"); content.put("kind", "EVENT");
        content.put("modelVersion", message.modelVersion()); content.put("eventKey", message.eventKey());
        content.put("occurredAt", message.occurredAt().toString()); content.put("params", message.params());
        return json.writeValueAsString(content);
    }

    /** 内部端口也拒绝不完整或协议不适用信封，不能绕过独立MQTT事件资格。 */
    private static void validateEnvelope(EventUplinkMessage message) {
        if (message == null || message.messageId() == null || message.messageId().version() != 7
                || message.tenantId() == null || message.projectId() == null || message.deviceId() == null
                || message.protocol() != TransportProtocol.MQTT || message.params() == null
                || message.eventKey() == null || !message.eventKey().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || message.modelVersion() == null || message.occurredAt() == null || message.receivedAt() == null
                || message.occurredAt().isAfter(message.receivedAt().plusSeconds(300))
                || message.rawBytes() < 0 || message.rawBytes() > 65536) {
            throw new BusinessException(EventErrorCode.EVENT_INVALID);
        }
    }
}
