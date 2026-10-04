package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.MessageLogQuery;
import com.things.link.telemetry.domain.MessageLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 设备消息日志写入与查询服务。 */
@Service
public class MessageLogService {
    /** 单条载荷摘要的最大 Unicode 码点数，避免日志表退化为原始报文仓库。 */
    private static final int MAX_PAYLOAD_SUMMARY_CODE_POINTS = 256;
    /** 消息日志仓储。 */
    private final MessageLogRepository repository;
    /** 项目成员授权服务。 */
    private final ProjectService projectService;
    /** device 域公开的归属解析端口，禁止 telemetry 直接查询设备表。 */
    private final DeviceIngestionService deviceIngestionService;
    /** device 域公开的控制面详情端口，查询时统一返回设备领域错误码。 */
    private final DeviceService deviceService;
    /** 调试数据脱敏器：写入与读取各脱敏一次（历史行只能靠读取层兜住）。 */
    private final MessageLogRedactor redactor;

    /**
     * 创建消息日志服务。
     *
     * @param repository 消息日志仓储
     * @param projectService 项目成员授权服务
     * @param deviceIngestionService 可信设备归属解析端口
     * @param deviceService 设备控制面详情端口
     * @param redactor 调试数据脱敏器
     */
    public MessageLogService(MessageLogRepository repository, ProjectService projectService,
                             DeviceIngestionService deviceIngestionService,
                             DeviceService deviceService,
                             MessageLogRedactor redactor) {
        this.repository = repository;
        this.projectService = projectService;
        this.deviceIngestionService = deviceIngestionService;
        this.deviceService = deviceService;
        this.redactor = redactor;
    }

    /**
     * 幂等记录一条经过接入层标准化的设备消息。
     *
     * @param command 消息日志命令
     * @return true 表示首次写入，false 表示相同 messageId 已记录
     */
    @Transactional
    public boolean log(DeviceMessageLogCommand command) {
        validateCommand(command);
        UUID tenantId = deviceIngestionService.requireDeviceOwner(
                command.projectId(), command.deviceId()).tenantId();
        Instant now = Instant.now();
        // 先脱敏再截断：凭据类字段从不落库；截断标记按脱敏后的文本判定。
        Summary summary = summarize(redactor.redact(command.payloadSummary()));
        // 阶段时刻只写我们真正知道的两段：受理＝平台收到该消息的时刻；处理完成＝本次落库时刻。
        // 解析／投递／回复三段由各自阶段写入（未发生即为空），绝不用当前时刻冒充。
        DeviceMessageLog entry = new DeviceMessageLog(Uuid7.generate(), command.projectId(), command.deviceId(),
                command.messageId(), tenantId, command.protocol(), command.direction(),
                normalize(command.topic(), 512), summary.text(), command.rawBytes(),
                normalize(command.errorCode(), 32), command.occurredAt(), command.receivedAt(),
                normalize(command.traceId(), 64), now, normalize(command.messageType(), 32),
                command.receivedAt(), null, now, command.deliveredAt(), command.repliedAt(),
                summary.truncated(), false);
        if (!repository.tryAcquire(entry)) {
            return false;
        }
        repository.save(entry);
        return true;
    }

    /**
     * 查询当前项目成员可见的设备消息日志。
     *
     * @param query 查询条件
     * @return 一页消息日志
     */
    @Transactional(readOnly = true)
    public CursorPage<DeviceMessageLog> list(MessageLogQuery query) {
        projectService.requireRoleInProject(query.projectId());
        if (query.deviceId() != null) {
            // 跨设备项目查询不做逐设备 N+1 校验；指定设备时仍隐藏跨项目资源存在性。
            deviceService.detail(query.projectId(), query.deviceId());
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "开始时间必须早于结束时间");
        }
        return repository.find(query);
    }

    /**
     * 读取一条消息日志详情。
     *
     * <p>读取层再脱敏一次：脱敏器上线之前写入的历史行也必须在调试面上表现为"没有凭据"。找不到该行（含跨项目、
     * 跨设备）一律抛设备域 404，不区分"不存在"与"不属于你"——避免把日志 ID 变成存在性探针。</p>
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param logId 日志 ID
     * @return 详情
     */
    @Transactional(readOnly = true)
    public DeviceMessageLog detail(UUID projectId, UUID deviceId, UUID logId) {
        projectService.requireRoleInProject(projectId);
        deviceService.detail(projectId, deviceId);
        DeviceMessageLog entry = repository.findByLogId(projectId, deviceId, logId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "消息日志不存在"));
        return new DeviceMessageLog(entry.id(), entry.projectId(), entry.deviceId(), entry.messageId(),
                entry.tenantId(), entry.protocol(), entry.direction(), entry.topic(),
                redactor.redact(entry.payloadSummary()), entry.rawBytes(), entry.errorCode(), entry.ts(),
                entry.receivedAt(), entry.traceId(), entry.createdAt(), entry.messageType(), entry.acceptedAt(),
                entry.parsedAt(), entry.processedAt(), entry.deliveredAt(), entry.repliedAt(), entry.truncated(),
                entry.sampled());
    }

    /** 校验内部消息契约；无效消息不得污染写多读少的大表。 */
    private static void validateCommand(DeviceMessageLogCommand command) {
        if (command == null || command.projectId() == null || command.deviceId() == null
                || command.messageId() == null || command.protocol() == null || command.direction() == null
                || command.occurredAt() == null || command.receivedAt() == null || command.rawBytes() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备消息日志参数不合法");
        }
    }

    /** 对有数据库长度上限的可选文本执行去空白与截断。 */
    private static String normalize(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= maximumLength ? stripped : stripped.substring(0, maximumLength);
    }

    /** 按 Unicode 码点截断摘要，避免把代理对从中间切断。 */
    private static Summary summarize(String value) {
        if (value == null || value.isBlank()) {
            return new Summary(null, false);
        }
        String stripped = value.strip();
        String trimmed = stripped.codePoints().limit(MAX_PAYLOAD_SUMMARY_CODE_POINTS)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        // 截断必须**标记**（§7.3）：否则调试面会把"看到的摘要"误当成完整报文。
        return new Summary(trimmed, trimmed.length() < stripped.length());
    }

    /**
     * 摘要与截断标记。
     *
     * @param text 已截断的摘要
     * @param truncated 是否发生了截断
     */
    private record Summary(String text, boolean truncated) {
    }

}
