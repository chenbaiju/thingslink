package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceCommandDefinition;
import com.things.link.device.domain.DeviceCommandDefinitionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceShadow;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * 数据面访问设备聚合的公开应用端口。
 *
 * <p>架构文档 10.4 明确禁止 telemetry 直接读写 {@code dev_*} 表。该端口集中承担设备归属、
 * 物模型校验与影子 CAS，使高频摄入仍能加入调用方事务，同时保持表所有权不越界。</p>
 */
@Service
public class DeviceIngestionService {
    /** 缓存故障只记录告警，架构文档 11.3 节禁止其反向污染事实事务。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceIngestionService.class);
    /** 设备聚合仓储。 */
    private final DeviceRepository deviceRepository;
    /** 原事务Webhook来源，关闭时不改变既有数据面。 */
    private final DevicePropertyWebhookSource webhookSource;
    /** 设备类型仓储，用于解析设备分类以拒绝未绑定子设备下行。 */
    private final DeviceTypeRepository typeRepository;
    /** 物模型属性仓储。 */
    private final DevicePropertyDefinitionRepository propertyRepository;
    /** 设备影子仓储。 */
    private final DeviceShadowRepository shadowRepository;
    /** 属性值必须由统一序列化器生成 JSON，禁止字符串拼接。 */
    private final ObjectMapper objectMapper;
    /** PostgreSQL 事务提交后的 Redis 热影子端口。 */
    private final DeviceCurrentValueCache currentValueCache;
    /** 命令定义仓储，仅在设备模块内部解析已发布物模型签名。 */
    private final DeviceCommandDefinitionRepository commandDefinitionRepository;
    /** 命令请求与响应必须复用物模型实例校验器。 */
    private final ThingModelSchemaValidator schemaValidator;
    /** MQTT Topic 需要项目稳定键，跨模块只能经 project application 端口取得。 */
    private final ProjectService projectService;
    /** 事实提交后的实时增量由 ingestion 异步处理，device 不直接依赖 Kafka 或 Redis 频道。 */
    private final ApplicationEventPublisher eventPublisher;
    /** B-X1a 冻结的版本绑定裁决端口；B-X1b 下游只消费其 CURRENT/HISTORY_ONLY 结论。 */
    private final ThingModelVersionBindingService versionBindingService;
    /** 无 HTTP 上下文的 Kafka 入口必须在仓储 SQL 前集中建立可信租户/项目事务范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /**
     * 创建设备数据面应用端口。
     *
     * @param deviceRepository 设备仓储
     * @param propertyRepository 属性定义仓储
     * @param shadowRepository 影子仓储
     * @param objectMapper JSON 序列化器
     * @param currentValueCache Redis 热影子端口
     */
    public DeviceIngestionService(DeviceRepository deviceRepository,
                                  DeviceTypeRepository typeRepository,
                                  DevicePropertyDefinitionRepository propertyRepository,
                                  DeviceShadowRepository shadowRepository,
                                  ObjectMapper objectMapper,
                                  DeviceCurrentValueCache currentValueCache,
                                  DeviceCommandDefinitionRepository commandDefinitionRepository,
                                  ThingModelSchemaValidator schemaValidator,
                                  ProjectService projectService,
                                  ApplicationEventPublisher eventPublisher,
                                  ThingModelVersionBindingService versionBindingService,
                                  TransactionLocalRlsScope transactionLocalRlsScope, DevicePropertyWebhookSource webhookSource) {
        this.deviceRepository = deviceRepository;
        this.webhookSource = webhookSource;
        this.typeRepository = typeRepository;
        this.propertyRepository = propertyRepository;
        this.shadowRepository = shadowRepository;
        this.objectMapper = objectMapper;
        this.currentValueCache = currentValueCache;
        this.commandDefinitionRepository = commandDefinitionRepository;
        this.schemaValidator = schemaValidator;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
        this.versionBindingService = versionBindingService;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
    }

    /**
     * 解析命令下行的可信目标、连接路由和物模型快照。
     *
     * <p>ADR 0017 要求 target 与 connection 分开：直连设备二者相同，子设备使用 gatewayId 指向的网关。
     * 调用方不得从请求体接受这些字段，否则可绕过项目隔离或向另一设备 Topic 下发。</p>
     *
     * @param projectId 已选择项目
     * @param targetDeviceId 目标设备
     * @param commandKey 物模型命令标识
     * @param input 命令输入
     * @return 下行可靠链路所需的不可变路由与命令签名快照
     */
    @Transactional(readOnly = true)
    public DeviceCommandRoute resolveCommandRoute(
            UUID projectId, UUID targetDeviceId, String commandKey, JsonNode input) {
        Device target = requireDevice(projectId, targetDeviceId);
        if (target.deviceTypeId() == null)
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "设备尚未绑定设备类型");
        Device connection = resolveConnectionDevice(projectId, target);
        DeviceCommandDefinition definition = commandDefinitionRepository.findByCommandKey(
                projectId, target.deviceTypeId(), commandKey)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_NOT_FOUND));
        try {
            schemaValidator.validateInstance(definition.inputSchema(), input);
        } catch (InvalidThingModelSchemaException exception) {
            throw new BusinessException(DeviceErrorCode.COMMAND_REQUEST_INVALID);
        }
        ProjectService.ProjectRoutingContext project = projectService.requireRoutingContext(projectId);
        if (!project.tenantId().equals(target.tenantId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "设备不存在");
        return new DeviceCommandRoute(target.tenantId(), project.projectKey(), target.id(), target.deviceKey(),
                connection.id(), connection.deviceKey(), definition.id(), definition.commandKey(),
                definition.inputSchema(), definition.outputSchema(), definition.timeoutSeconds());
    }

    /**
     * 解析属性设置的可信目标与连接路由，并按物模型逐项校验可下发性和值约束。
     *
     * <p>S9-2 后台规则动作只提交目标设备与属性对象；tenant、项目键、连接网关与设备键都由权威事实解析，
     * 防止动作配置借 payload 自报字段跨设备下发。</p>
     *
     * @param projectId 已确权项目
     * @param targetDeviceId 业务目标设备
     * @param properties 待设置属性对象
     * @return 属性设置下行所需的可信路由
     */
    @Transactional(readOnly = true)
    public DevicePropertySetRoute resolvePropertySetRoute(
            UUID projectId, UUID targetDeviceId, JsonNode properties) {
        Device target = requireDevice(projectId, targetDeviceId);
        if (target.deviceTypeId() == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "设备尚未绑定设备类型");
        }
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_CONSTRAINT_INVALID,
                    "属性设置必须是非空对象");
        }
        properties.properties().forEach(entry -> {
            DevicePropertyDefinition definition = propertyRepository.findByPropertyKey(
                            projectId, target.deviceTypeId(), entry.getKey())
                    .orElseThrow(() -> new BusinessException(
                            CommonErrorCode.RESOURCE_NOT_FOUND, "属性定义不存在"));
            if (definition.accessType() != DevicePropertyDefinition.AccessType.DOWNLINK
                    && definition.accessType() != DevicePropertyDefinition.AccessType.SHARED) {
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_CONSTRAINT_INVALID,
                        "属性不允许云端下发");
            }
            validateValue(definition, objectMapper.convertValue(entry.getValue(), Object.class));
        });
        Device connection = resolveConnectionDevice(projectId, target);
        ProjectService.ProjectRoutingContext project = projectService.requireRoutingContext(projectId);
        if (!project.tenantId().equals(target.tenantId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "设备不存在");
        }
        return new DevicePropertySetRoute(target.tenantId(), project.projectKey(), target.id(), target.deviceKey(),
                connection.id(), connection.deviceKey());
    }

    /**
     * 校验设备命令成功回复的输出载荷。
     * @param outputSchema 命令创建时冻结的输出 Schema
     * @param output 设备响应输出
     */
    public void validateCommandOutput(String outputSchema, JsonNode output) {
        schemaValidator.validateInstance(outputSchema, output);
    }

    /**
     * 校验整批上报属性并返回可信设备归属。
     *
     * <p>全部属性在调用方抢占 inbox 之前校验，错误报文修正后仍可使用原 messageId 重发。</p>
     *
     * @param projectId Topic 派生的项目 ID
     * @param deviceId Topic 派生的设备 ID
     * @param properties 待上报属性
     * @return 数据面后续写入可使用的可信设备归属
     */
    @Transactional(readOnly = true)
    public DeviceOwnerContext validateReportedProperties(
            UUID projectId, UUID deviceId, Map<String, Object> properties) {
        Device device = requireDevice(projectId, deviceId);
        if (device.deviceTypeId() == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "设备尚未绑定设备类型");
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            DevicePropertyDefinition definition = propertyRepository.findByPropertyKey(
                            projectId, device.deviceTypeId(), property.getKey())
                    .orElseThrow(() -> new BusinessException(
                            CommonErrorCode.RESOURCE_NOT_FOUND, "属性定义不存在"));
            requireReportable(definition);
            validateValue(definition, property.getValue());
        }
        return new DeviceOwnerContext(device.tenantId());
    }

    /**
     * 按设备声明的不可变版本校验整批属性，并返回一次性 CURRENT/HISTORY_ONLY 裁决。
     *
     * <p>定义必须取版本快照而不是当前 {@code dev_property_definition}；否则 OTA 切换后的旧消息会被
     * 新 Schema 重解释。服务端可信 {@code receivedAt} 只交给绑定服务判定资格，设备时间不能延长窗口。</p>
     *
     * @param tenantId 已由标准消息信封认证的可信租户
     * @param projectId 已确权项目
     * @param deviceId 已认证设备
     * @param modelVersion 设备声明语义版本
     * @param receivedAt 接入时冻结的平台时间
     * @param properties 待校验属性批次
     * @return 携带写入时版本、类型映射和不可提升资格的上下文
     */
    @Transactional(readOnly = true)
    public com.things.link.device.application.DeviceIngestionContext validateReportedProperties(
            UUID tenantId, UUID projectId, UUID deviceId, String modelVersion, Instant receivedAt,
            Map<String, Object> properties) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        Device device = requireDevice(projectId, deviceId);
        if (device.deviceTypeId() == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "设备尚未绑定设备类型");
        }
        com.things.link.device.application.DeviceIngestionContext context =
                versionBindingService.resolveForIngestion(projectId, deviceId, modelVersion, receivedAt);
        if (!device.tenantId().equals(context.tenantId())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "物模型版本租户归属不一致");
        }
        JsonNode definitions = objectMapper.readTree(context.modelSnapshot()).get("properties");
        Map<String, String> types = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            JsonNode definition = definitions == null ? null : definitions.get(property.getKey());
            if (definition == null || !definition.isObject()) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "版本化属性定义不存在");
            }
            String accessType = definition.path("accessType").asString();
            if (!"REPORT".equals(accessType) && !"SHARED".equals(accessType)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "该属性不允许设备上报");
            }
            String dataType = definition.path("dataType").asString();
            validateVersionedValue(definition, dataType, property.getValue());
            types.put(property.getKey(), dataType);
        }
        // Object/List 从第一天强制声明版本；存量标量省略推断链路一旦携带复合属性即协议违约，不能静默放行。
        if (context.legacyInferred()
                && types.values().stream().anyMatch(type -> "OBJECT".equals(type) || "LIST".equals(type))) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        }
        return context.withPropertyDataTypes(types);
    }

    /**
     * 校验直连MQTT设备的事件原参数，并冻结不可变模型中的级别与原接收资格。
     *
     * <p>只供已认证摄取链首次事务调用；已成功重放由telemetry先比较原事实，不重新裁决旧资格。
     * 设备当前定义、发生时间、连接在线态和可变事件名称均不参与历史解释，也不产生属性副作用。</p>
     *
     * @param tenantId 接入层确权的实际租户
     * @param projectId 接入层确权的项目
     * @param deviceId 连接设备本身，不能代报子设备
     * @param modelVersion 明确声明的原语义版本
     * @param receivedAt 可信接入时间，唯一旧版窗口依据
     * @param eventKey MQTT主题中的事件标识
     * @param params 未脱敏的实际参数，校验完成后由事实域执行安全投影
     * @return 原设备类型、不可变模型摘要、事件级别和不能提升的资格
     */
    @Transactional(readOnly = true)
    public DeviceEventIngestionContext validateReportedEvent(
            UUID tenantId, UUID projectId, UUID deviceId, String modelVersion, Instant receivedAt,
            String eventKey, Map<String, Object> params) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        Device device = requireDevice(projectId, deviceId);
        if (!tenantId.equals(device.tenantId()) || !projectId.equals(device.projectId())) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        if (device.deviceTypeId() == null) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND);
        }
        DeviceType type = typeRepository.findById(projectId, device.deviceTypeId())
                .filter(value -> tenantId.equals(value.tenantId()) && projectId.equals(value.projectId())
                        && device.deviceTypeId().equals(value.id()))
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
        if (type.status() != DeviceType.Status.PUBLISHED || device.gatewayId() != null
                || (type.deviceKind() != DeviceType.DeviceKind.DIRECT && type.deviceKind() != DeviceType.DeviceKind.GATEWAY)) {
            throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        }
        if (modelVersion == null || !modelVersion.matches("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        }
        for (String part : modelVersion.split("\\.")) {
            if (Integer.parseInt(part) > 65535) throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        }
        if (receivedAt == null || eventKey == null || !eventKey.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        }
        DeviceIngestionContext context = versionBindingService.resolveForIngestion(projectId, deviceId, modelVersion, receivedAt);
        if (!tenantId.equals(context.tenantId()) || !modelVersion.equals(context.versionNumber()) || context.legacyInferred()) {
            throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        }
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(context.modelSnapshot());
        } catch (JacksonException | IllegalArgumentException exception) {
            // 只把无法解析的旧快照映射为永久定义拒绝，不吞设备仓储或绑定服务的数据库故障。
            throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        }
        if (snapshot == null || !snapshot.isObject()) throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        JsonNode events = snapshot.get("events");
        DeviceEventSchema.validateDefinitions(events);
        JsonNode event = events.get(eventKey);
        if (event == null) throw new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
        DeviceEventSchema.validateParameters(event, params);
        return new DeviceEventIngestionContext(tenantId, device.deviceTypeId(), context.thingModelVersionId(),
                context.versionNumber(), context.schemaDigest(), context.digestAlgorithm(), eventKey,
                event.path("level").asString(), context.eligibility());
    }

    /**
     * 返回消息日志所需的可信设备归属，调用方不得接受信封自报的 tenantId。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 可信设备归属
     */
    @Transactional(readOnly = true)
    public DeviceOwnerContext requireDeviceOwner(UUID projectId, UUID deviceId) {
        return new DeviceOwnerContext(requireDevice(projectId, deviceId).tenantId());
    }

    /**
     * 将一批已校验属性合入 reported 影子。
     *
     * <p>本方法加入 telemetry 已开启的事务；时序点、消息日志和影子任一步失败都会整体回滚。
     * 每个属性独立比较采集时间，保证同一批之外的乱序报文不会覆盖更新值。</p>
     *
     * @param context 由本服务校验得到的可信归属
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param properties 已校验属性
     * @param occurredAt 设备采集时间
     */
    @Transactional
    public void mergeReportedProperties(com.things.link.device.application.DeviceIngestionContext context,
                                        UUID projectId, UUID deviceId,
                                        Map<String, Object> properties, Instant occurredAt) {
        mergeReportedProperties(context, projectId, deviceId, properties, occurredAt,
                Uuid7.generate(), TraceContext.resolve(TraceContext.current()));
    }

    /**
     * 将一批已校验属性合入 reported 影子，并发布携带原始上行身份的实时增量事件。
     *
     * <p>只有 PostgreSQL 属性 CAS 接受的值才会进入事件；若把原始报文整体发布，迟到属性会
     * 在浏览器中重新显示成当前值，破坏 S4-2 按事件时间保护的影子语义。事件在事务内发布，
     * 监听器则在提交后异步投递，因此 Redis/Kafka 故障不会回滚遥测事实。</p>
     *
     * @param context 已校验的设备可信归属
     * @param projectId 项目隔离轴
     * @param deviceId 设备 ID
     * @param properties 已通过物模型校验的属性
     * @param occurredAt 设备采集时间
     * @param messageId 原始上行消息 ID
     * @param traceId 原始上行追踪标识
     */
    @Transactional
    public void mergeReportedProperties(com.things.link.device.application.DeviceIngestionContext context,
                                        UUID projectId, UUID deviceId,
                                        Map<String, Object> properties, Instant occurredAt,
                                        UUID messageId, String traceId) {
        if (context.eligibility()
                != com.things.link.device.application.DeviceIngestionContext.Eligibility.CURRENT) {
            throw new IllegalArgumentException("HISTORY_ONLY 上下文不得更新影子或发布实时增量");
        }
        var webhookGeneration=webhookSource.begin(context.tenantId(),projectId);
        shadowRepository.createIfAbsent(new DeviceShadow(
                deviceId, context.tenantId(), projectId, null, null, 0, Instant.now()));
        Map<String, String> jsonValues = new LinkedHashMap<>();
        Map<String, String> reportedRevisions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String jsonValue = objectMapper.writeValueAsString(property.getValue());
            java.util.OptionalLong accepted = shadowRepository.updateReportedPropertyIfNewer(
                    projectId, deviceId, property.getKey(), jsonValue, occurredAt, context.thingModelVersionId());
            if (accepted.isPresent()) {
                // 保存 PostgreSQL CAS 的实际接受序号；缓存读取仍须核验 PG 当前序号，防止淘汰后旧回调复活。
                jsonValues.put(property.getKey(), jsonValue);
                reportedRevisions.put(property.getKey(), Long.toString(accepted.getAsLong()));
            }
        }
        if (jsonValues.isEmpty()) {
            return;
        }
        int shadowVersion = shadowRepository.findByDevice(projectId, deviceId)
                .map(DeviceShadow::version).orElse(0);
        updateCacheAfterCommit(projectId, deviceId, jsonValues, occurredAt, shadowVersion,
                reportedRevisions, context.thingModelVersionId());
        // 在事务内发布、由 AFTER_COMMIT 监听器异步处理：回滚不广播，提交不等待网络 I/O。
        var update=new DeviceRealtimeUpdate(messageId, context.tenantId(), projectId, deviceId, context.thingModelVersionId(),
                context.versionNumber(), occurredAt, shadowVersion, traceId, jsonValues,
                filterTypes(context.propertyDataTypes(), jsonValues), reportedRevisions);
        if(webhookGeneration.isPresent())webhookSource.append(update,webhookGeneration.getAsLong());
        eventPublisher.publishEvent(new DeviceReportedPropertiesCommitted(update));
    }

    /**
     * 仅在 PostgreSQL 事实事务提交后更新热副本；回调异常不得传播到已经成功的上行链路。
     */
    private void updateCacheAfterCommit(UUID projectId, UUID deviceId, Map<String, String> jsonValues,
                                        Instant occurredAt, int shadowVersion, Map<String, String> revisions, UUID source) {
        Map<String, com.things.link.device.domain.DeviceCurrentValueCache.ReportedValue> facts = new LinkedHashMap<>();
        jsonValues.forEach((key, json) -> facts.put(key, new com.things.link.device.domain.DeviceCurrentValueCache.ReportedValue(
                json, occurredAt, shadowVersion, revisions.get(key), source)));
        Runnable update = () -> {
            try {
                currentValueCache.merge(projectId, deviceId, facts);
            } catch (RuntimeException exception) {
                LOGGER.warn("Redis 热影子更新失败，等待批量查询回源 PostgreSQL: projectId={}, deviceId={}",
                        projectId, deviceId, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 单元测试或显式无事务调用没有提交回调；此分支仅维持端口可独立使用，生产摄入始终加入外层事务。
            update.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 事务回滚时不会触发，避免缓存出现 PostgreSQL 中不存在的值。 */
            @Override
            public void afterCommit() {
                update.run();
            }
        });
    }

    /** 查询项目内未删除设备。 */
    private Device requireDevice(UUID projectId, UUID deviceId) {
        return deviceRepository.findById(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "设备不存在"));
    }

    /**
     * 解析下行实际连接设备：子设备经 gateway_id 投影指向网关；直连/网关路由到自身。
     * 未绑定网关的子设备（gateway_id 为空）或网关离线时都无法下行，fail-fast 拒绝而非发往无订阅者的死 Topic
     * 或空等超时（D-027）。
     */
    private Device resolveConnectionDevice(UUID projectId, Device target) {
        if (target.gatewayId() != null) {
            Device connection = requireDevice(projectId, target.gatewayId());
            if (connection.status() != Device.Status.ONLINE) {
                throw new BusinessException(DeviceErrorCode.GATEWAY_OFFLINE);
            }
            return connection;
        }
        DeviceType type = typeRepository.findById(projectId, target.deviceTypeId()).orElse(null);
        if (type != null && type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) {
            throw new BusinessException(DeviceErrorCode.SUB_DEVICE_UNBOUND);
        }
        return target;
    }

    /** 限制为设备可上报或双向共享的属性。 */
    private static void requireReportable(DevicePropertyDefinition definition) {
        if (definition.accessType() != DevicePropertyDefinition.AccessType.REPORT
                && definition.accessType() != DevicePropertyDefinition.AccessType.SHARED) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "该属性不允许设备上报");
        }
    }

    /** 按物模型基础类型校验设备值。 */
    private static void validateValue(DevicePropertyDefinition definition, Object value) {
        switch (definition.dataType()) {
            case NUMBER -> validateNumber(definition, value);
            case TEXT -> {
                if (!(value instanceof String)) {
                    throwInvalidValue();
                }
            }
            case SWITCH -> {
                if (!(value instanceof Boolean)) {
                    throwInvalidValue();
                }
            }
            case ENUM -> {
                List<String> options = definition.enumOptions();
                if (!(value instanceof String text) || options == null || !options.contains(text)) {
                    throwInvalidValue();
                }
            }
        }
    }

    /** 按不可变版本快照验证标量或严格复合值，禁止回退读取当前定义。 */
    private void validateVersionedValue(JsonNode definition, String dataType, Object value) {
        switch (dataType) {
            case "NUMBER" -> validateVersionedNumber(definition, value);
            case "TEXT" -> {
                if (!(value instanceof String)) throwInvalidValue();
            }
            case "SWITCH" -> {
                if (!(value instanceof Boolean)) throwInvalidValue();
            }
            case "ENUM" -> {
                JsonNode options = definition.get("enum");
                if (!(value instanceof String text) || options == null || !options.isArray()
                        || !options.values().stream().anyMatch(option -> option.isString()
                        && option.asString().equals(text))) {
                    throwInvalidValue();
                }
            }
            case "OBJECT", "LIST" -> {
                JsonNode schema = definition.get("schema");
                JsonNode instance = objectMapper.valueToTree(value);
                try {
                    schemaValidator.validateInstance(schema == null ? null : schema.toString(), instance,
                            ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
                } catch (InvalidThingModelSchemaException exception) {
                    throwInvalidValue();
                }
            }
            default -> throwInvalidValue();
        }
    }

    /** 版本快照的数值边界与定义保存使用同一字段名。 */
    private static void validateVersionedNumber(JsonNode definition, Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throwInvalidValue();
            return;
        }
        double candidate = number.doubleValue();
        JsonNode minimum = definition.get("minimum");
        JsonNode maximum = definition.get("maximum");
        if (minimum != null && candidate < minimum.asDouble()
                || maximum != null && candidate > maximum.asDouble()) {
            throwInvalidValue();
        }
    }

    /** 实时事件只携带 CAS 实际接受的属性类型，键集必须与值映射精确一致。 */
    private static Map<String, String> filterTypes(Map<String, String> types, Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.keySet().forEach(key -> result.put(key, types.get(key)));
        return result;
    }

    /** 校验数值类型、有限值及物模型量程。 */
    private static void validateNumber(DevicePropertyDefinition definition, Object value) {
        if (!(value instanceof Number number)) {
            throwInvalidValue();
            return;
        }
        double candidate = number.doubleValue();
        if (!Double.isFinite(candidate)
                || definition.minimumValue() != null
                && candidate < definition.minimumValue().doubleValue()
                || definition.maximumValue() != null
                && candidate > definition.maximumValue().doubleValue()) {
            throwInvalidValue();
        }
    }

    /** 抛出跨模块稳定复用的属性值错误语义。 */
    private static void throwInvalidValue() {
        throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "属性值与物模型定义不匹配");
    }

    /**
     * 设备数据面公开的最小归属投影。
     *
     * @param tenantId 从设备档案派生的租户 ID
     */
    public record DeviceOwnerContext(UUID tenantId) {
    }

    /**
     * 命令下行可信投影；只暴露跨模块需要的稳定标量，不泄露 device domain 聚合。
     *
     * @param tenantId 租户 ID @param projectKey MQTT 项目键
     * @param targetDeviceId 业务目标设备 @param targetDeviceKey 目标设备键
     * @param connectionDeviceId 实际连接设备 @param connectionDeviceKey 实际连接设备键
     * @param commandDefinitionId 命令定义 ID @param commandKey 命令标识
     * @param inputSchema 输入 Schema 快照 @param outputSchema 输出 Schema 快照
     * @param timeoutSeconds 每次尝试等待秒数
     */
    public record DeviceCommandRoute(UUID tenantId, String projectKey,
                                     UUID targetDeviceId, String targetDeviceKey,
                                     UUID connectionDeviceId, String connectionDeviceKey,
                                     UUID commandDefinitionId, String commandKey,
                                     String inputSchema, String outputSchema, int timeoutSeconds) {
    }

    /** 属性设置只冻结可信设备路由；值约束已在返回前完成校验。 */
    public record DevicePropertySetRoute(UUID tenantId, String projectKey,
                                         UUID targetDeviceId, String targetDeviceKey,
                                         UUID connectionDeviceId, String connectionDeviceKey) {
    }
}
