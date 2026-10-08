package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.PlanHistoryWindow;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.api.dto.response.DeviceEventPageResponse;
import com.things.link.telemetry.api.dto.response.DeviceEventResponse;
import com.things.link.telemetry.domain.DeviceEventHistoryItem;
import com.things.link.telemetry.domain.DeviceEventHistoryRepository;
import com.things.link.telemetry.domain.EventErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 每页重新确定授权、归属和数据库权益；原事实不依赖当前模型资格。 */
@Service
public class EventHistoryService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceIngestionService devices;
    private final PlanCapacityService capacity;
    private final DeviceEventHistoryRepository repository;
    private final MessageLogRedactor redactor;
    private final ObjectMapper json;
    /** @param projects 当前成员授权 @param scope 所属租户事务范围 @param devices 当前设备归属
     * @param capacity 权威数据库窗口 @param repository 原发生事实 @param json 精确JSON配置 */
    public EventHistoryService(ProjectService projects, TransactionLocalRlsScope scope, DeviceIngestionService devices,
            PlanCapacityService capacity, DeviceEventHistoryRepository repository, ObjectMapper json) {
        this.projects = projects; this.scope = scope; this.devices = devices; this.capacity = capacity;
        this.repository = repository;
        this.json = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        // 脱敏也必须使用同一精确解析器，避免读时重序列化已舍入的历史小数。
        this.redactor = new MessageLogRedactor(this.json);
    }
    /** @param project 项目 @param device 当前设备 @param parameters 原始封闭查询 @return 有效窗口内的原事实键集页 */
    @Transactional(readOnly = true)
    public DeviceEventPageResponse list(UUID project, UUID device, Map<String, String[]> parameters) {
        UUID owner = authorize(project, device);
        var query = EventHistoryQuery.parse(parameters);
        var anchor = EventHistoryCursor.decode(query.cursor(), project, device, query);
        var window = window(capacity.historyWindow(owner, project), query.lower(), query.upper());
        if (!window.from().isBefore(window.to())) return new DeviceEventPageResponse(List.of(), null, window.from(), window.to(), 90);
        var rows = repository.find(project, device, query.eventKey(), query.level(), query.versionId(), window.from(), window.to(),
                anchor == null ? null : anchor.occurredAt(), anchor == null ? null : anchor.messageId(), query.limit() + 1);
        boolean more = rows.size() > query.limit();
        var page = more ? rows.subList(0, query.limit()) : rows;
        String cursor = null;
        if (more) { var last = page.getLast(); cursor = EventHistoryCursor.encode(project, device, query, last.occurredAt(), last.messageId()); }
        return new DeviceEventPageResponse(page.stream().map(this::response).toList(), cursor, window.from(), window.to(), 90);
    }
    /** @param project 项目 @param device 当前设备 @param message 消息标识 @param parameters 必须为空的详情query
     * @return 相同当前许可及窗口内原事实，不存在时统一30072 */
    @Transactional(readOnly = true)
    public DeviceEventResponse detail(UUID project, UUID device, UUID message, Map<String, String[]> parameters) {
        UUID owner = authorize(project, device);
        EventHistoryQuery.requireDetailParameters(parameters);
        var window = window(capacity.historyWindow(owner, project), null, null);
        if (message == null || !window.from().isBefore(window.to())) throw new BusinessException(EventErrorCode.EVENT_NOT_FOUND);
        return response(repository.findOne(project, device, message, window.from(), window.to())
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND)));
    }
    /** 四角色均可读；只使用项目持久归属，不能把协作者home租户替换为owner身份。 */
    private UUID authorize(UUID project, UUID device) {
        projects.requireRoleInProject(project);
        UUID owner = projects.requireProjectTenant(project);
        scope.establish(owner, project);
        devices.requireDeviceOwner(project, device);
        return owner;
    }
    /** 同一权威数据库时刻与90天、原始请求求交；空交集归一为有效上界点。 */
    static PlanHistoryWindow window(PlanHistoryWindow plan, Instant requestedFrom, Instant requestedTo) {
        Instant lower = plan.from().isAfter(plan.to().minus(Duration.ofDays(90))) ? plan.from() : plan.to().minus(Duration.ofDays(90));
        Instant upper = plan.to();
        if (requestedFrom != null && requestedFrom.isAfter(lower)) lower = requestedFrom;
        if (requestedTo != null && requestedTo.isBefore(upper)) upper = requestedTo;
        if (lower.isAfter(upper)) lower = upper;
        return new PlanHistoryWindow(lower, upper);
    }
    /** 脱敏前后比较JSON语义，凭据消失不再要求原required字段存在。 */
    private DeviceEventResponse response(DeviceEventHistoryItem item) {
        String safe = redactor.redact(item.params());
        Map<String, Object> params = json.readValue(safe, new TypeReference<Map<String, Object>>() { });
        boolean changed = !json.readTree(item.params()).equals(json.readTree(safe));
        return new DeviceEventResponse(item.messageId(), item.deviceId(), item.deviceTypeId(), item.eventKey(), item.level(),
                item.thingModelVersionId(), item.modelVersion(), item.eligibility(), item.occurredAt(), item.receivedAt(), item.acceptedAt(),
                params, item.paramsRedacted() || changed);
    }
}
