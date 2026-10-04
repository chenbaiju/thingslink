package com.things.link.device.application;

import com.things.link.device.application.RuntimeDeviceCurrentResult.DeviceValues;
import com.things.link.device.application.RuntimeDeviceCurrentResult.PropertyValue;
import com.things.link.device.application.RuntimeDeviceCurrentResult.State;
import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRuntimeRepository;
import com.things.link.device.domain.DeviceRuntimeRepository.CurrentValueFact;
import com.things.link.device.domain.DeviceRuntimeRepository.DeviceFact;
import com.things.link.device.domain.DeviceRuntimeRepository.DeviceModelFact;
import com.things.link.device.domain.DeviceRuntimeRepository.DeviceRequest;
import com.things.link.device.domain.DeviceRuntimeRepository.PropertyRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 看板运行时指定设备、不可变模型描述与PG当前值公开application端口。
 *
 * <p>S12-0b2§3.1/3.2要求不可见和模型失配先于模型键解释，并禁止Redis缺字段降级。本服务每个
 * 查询使用一条普通项目RLS SQL形成设备、模型和当前值观察；App调用方仍须先在enduser域证明用户绑定。</p>
 */
@Service
public class DeviceRuntimeDataService {

    /** 单次最多20台设备。 */
    private static final int MAX_DEVICES = 20;
    /** 每台最多50个顶层属性。 */
    private static final int MAX_PROPERTIES_PER_DEVICE = 50;
    /** 单次最多200个精确设备属性组合。 */
    private static final int MAX_PROPERTY_COMBINATIONS = 200;
    /** 快照请求最多声明20个不可变模型。 */
    private static final int MAX_MODELS = 20;
    /** 运行数据只接受当前已冻结的模型Profile。 */
    private static final String MODEL_PROFILE = "TC_PROPERTY_COMPOSITE_V1";
    /** 物模型摘要只接受PG规范JSONB文本算法。 */
    private static final String DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 属性键与物模型合同共用安全字符集。 */
    private static final String PROPERTY_KEY = "[A-Za-z0-9_-]{1,64}";

    /** 有界PG事实仓储。 */
    private final DeviceRuntimeRepository repository;
    /** Spring统一JSON映射器。 */
    private final ObjectMapper objectMapper;
    /** 复合属性定义和实例共用的冻结校验器。 */
    private final ThingModelSchemaValidator schemaValidator;

    /**
     * @param repository 单SQL运行事实仓储
     * @param objectMapper 统一JSON映射器
     * @param schemaValidator 复合属性校验器
     */
    public DeviceRuntimeDataService(DeviceRuntimeRepository repository, ObjectMapper objectMapper,
                                    ThingModelSchemaValidator schemaValidator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        // 仅运行数据读取派生精确mapper；PG已保存的模型及当前值不得再经过DoubleNode。
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").rebuild()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
    }

    /** ADR0178：本领域持设备行锁，调用方仍负责身份/绑定授权。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockForRealtimeDelivery(UUID projectId,List<RuntimeDeviceQuery> requests){
        var validated=validateQueries(projectId,requests,true);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("设备出站锁要求非只读事务");
        if(repository.lockDevices(projectId,validated.stream().map(RuntimeDeviceQuery::deviceId).sorted().toList())!=validated.size())throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        requireAllAvailable(projectId,validated);
    }

    /**
     * 批量复核指定设备是否可见且仍绑定预期模型。
     * @param projectId 已建立普通RLS范围的可信项目
     * @param requests 已由上游计划限制的设备请求
     * @return 按请求顺序的三态投影
     */
    @Transactional(readOnly = true)
    public List<RuntimeDeviceAvailability> inspect(UUID projectId, List<RuntimeDeviceQuery> requests) {
        List<RuntimeDeviceQuery> validated = validateQueries(projectId, requests, true);
        return availability(validated, repository.inspect(projectId, deviceRequests(validated)));
    }

    /**
     * 实时授权读取指定设备当前模型，不把客户端猜测的版本当作设备事实。
     *
     * @param projectId 已在同事务建立普通RLS的项目
     * @param deviceIds 已由上游完整确权的1..20个设备
     * @return 完整设备到当前模型映射，缺失或未关联模型均拒绝
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> currentModelVersions(UUID projectId, Set<UUID> deviceIds) {
        if (projectId == null || deviceIds == null || deviceIds.isEmpty() || deviceIds.size() > MAX_DEVICES
                || deviceIds.stream().anyMatch(Objects::isNull)) throw invalid("实时设备集合不合法");
        List<UUID> ids = List.copyOf(deviceIds);
        List<DeviceRequest> requests = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            // inspect的普通RLS SQL只按项目和设备关联；expected列不参与连接，NULL不是模型通配授权。
            requests.add(new DeviceRequest(ids.get(index), null, index));
        }
        List<DeviceFact> facts = repository.inspect(projectId, requests);
        if (facts.size() != ids.size()) throw new IllegalStateException("实时设备模型仓储返回数量漂移");
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            DeviceFact fact = facts.get(index);
            if (!ids.get(index).equals(fact.deviceId()) || fact.position() != index) {
                throw new IllegalStateException("实时设备模型仓储返回身份漂移");
            }
            if (!fact.visible()) throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
            if (fact.currentModelVersionId() == null) throw invalid("实时设备尚未关联物模型");
            result.put(fact.deviceId(), fact.currentModelVersionId());
        }
        return Map.copyOf(result);
    }

    /** 批量摘要读取：当前版本和不可变模型内容一并验证，不能把损坏模型解释为健康设备。 */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> validatedCurrentModelVersions(UUID projectId, Set<UUID> deviceIds) {
        Map<UUID, UUID> versions = currentModelVersions(projectId, deviceIds);
        List<RuntimeDeviceQuery> queries = versions.entrySet().stream()
                .map(entry -> new RuntimeDeviceQuery(entry.getKey(), entry.getValue(), List.of())).toList();
        List<DeviceModelFact> facts = repository.snapshot(projectId, deviceRequests(queries));
        List<RuntimeDeviceAvailability> available = availability(queries, facts.stream().map(DeviceModelFact::device).toList());
        requireAllVisible(available);
        for (int index = 0; index < queries.size(); index++) {
            if (available.get(index).status() != RuntimeDeviceAvailability.Status.AVAILABLE) throw invalid("设备模型已变化");
            modelSnapshot(facts.get(index), queries.get(index).expectedModelVersionId());
        }
        return versions;
    }

    /**
     * S12-4b设计绑定入口从设备发现精确模型，不能要求浏览器预先猜测摘要。
     * 两次有界读取之间模型可能变化，第二次必须重新核对，禁止拼接不同模型事实。
     * @param projectId 已完成Console成员授权并建立普通RLS的项目
     * @param deviceId 用户明确选择的一台设备
     * @return 一台设备及其当前模型全部顶层属性，超出200项明确拒绝
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceSnapshotResult bindingMetadata(UUID projectId, UUID deviceId) {
        if (deviceId == null) throw invalid("绑定设备不能为空");
        UUID versionId = currentModelVersions(projectId, Set.of(deviceId)).get(deviceId);
        List<DeviceModelFact> facts = repository.snapshot(projectId,
                List.of(new DeviceRequest(deviceId, versionId, 0)));
        List<RuntimeDeviceAvailability> available = availability(
                List.of(new RuntimeDeviceQuery(deviceId, versionId, List.of())),
                facts.stream().map(DeviceModelFact::device).toList());
        requireAllVisible(available);
        if (available.getFirst().status() != RuntimeDeviceAvailability.Status.AVAILABLE) {
            throw invalid("设备模型已变化，请重新选择设备");
        }
        ModelSnapshot snapshot = modelSnapshot(facts.getFirst(), versionId);
        JsonNode properties = snapshot.root().path("properties");
        // 设计目录只允许完整有界投影，不能截断后让用户误以为模型没有其他属性。
        if (properties.size() > MAX_PROPERTY_COMBINATIONS) throw invalid("模型属性超过绑定目录200项上限");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        properties.propertyNames().forEach(keys::add);
        RuntimeModelDescription model = new RuntimeModelDescription(versionId, snapshot.digestAlgorithm(),
                snapshot.digest(), snapshot.profile(), describeProperties(snapshot, keys));
        return new RuntimeDeviceSnapshotResult(available, List.of(model));
    }

    /**
     * Console等严格读取要求全部设备可用时的便利入口。
     * @param projectId 已授权项目
     * @param requests 指定设备和精确模型
     * @return 全部可用设备事实
     */
    @Transactional(readOnly = true)
    public List<RuntimeDeviceAvailability> requireAllAvailable(
            UUID projectId, List<RuntimeDeviceQuery> requests) {
        List<RuntimeDeviceAvailability> result = inspect(projectId, requests);
        requireAllVisible(result);
        if (result.stream().anyMatch(item -> item.status() == RuntimeDeviceAvailability.Status.MODEL_MISMATCH)) {
            throw invalid("设备当前物模型与预期版本不一致");
        }
        return result;
    }

    /**
     * 要求指定设备均可见；模型失配仍由调用方按自身数据合同分类。
     * @param devices 已由{@link #inspect}形成的设备事实
     */
    public void requireAllVisible(List<RuntimeDeviceAvailability> devices) {
        if (devices == null || devices.stream().anyMatch(item -> item == null
                || item.status() == RuntimeDeviceAvailability.Status.NOT_AVAILABLE)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
    }

    /**
     * 返回设备基本事实与成功设备实际使用的不可变模型属性描述。
     * @param projectId 已建立普通RLS范围的可信项目
     * @param requests 已通过上游用户绑定过滤的请求设备
     * @param modelReferences 请求声明的模型身份
     * @return 设备三态和成功模型描述
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceSnapshotResult querySnapshots(UUID projectId, List<RuntimeDeviceQuery> requests,
                                                       List<RuntimeModelReference> modelReferences) {
        List<RuntimeDeviceQuery> validated = validateQueries(projectId, requests, true);
        Map<UUID, RuntimeModelReference> references = validateModelReferences(modelReferences);
        List<DeviceModelFact> facts = repository.snapshot(projectId, deviceRequests(validated));
        List<DeviceFact> deviceFacts = facts.stream().map(DeviceModelFact::device).toList();
        List<RuntimeDeviceAvailability> devices = availability(validated, deviceFacts);

        Map<UUID, LinkedHashSet<String>> requestedProperties = new LinkedHashMap<>();
        Map<UUID, ModelSnapshot> snapshots = new HashMap<>();
        for (int index = 0; index < devices.size(); index++) {
            RuntimeDeviceAvailability device = devices.get(index);
            if (device.status() != RuntimeDeviceAvailability.Status.AVAILABLE) continue;
            RuntimeDeviceQuery request = validated.get(index);
            RuntimeModelReference reference = references.get(request.expectedModelVersionId());
            if (reference == null) throw invalid("成功设备引用的模型未在models中声明");
            ModelSnapshot snapshot = modelSnapshot(facts.get(index), request.expectedModelVersionId());
            requireReference(reference, snapshot);
            snapshots.putIfAbsent(reference.versionId(), snapshot);
            requestedProperties.computeIfAbsent(reference.versionId(), ignored -> new LinkedHashSet<>())
                    .addAll(request.propertyKeys());
        }
        List<RuntimeModelDescription> models = new ArrayList<>();
        for (RuntimeModelReference reference : modelReferences) {
            LinkedHashSet<String> keys = requestedProperties.get(reference.versionId());
            if (keys == null) continue;
            ModelSnapshot snapshot = snapshots.get(reference.versionId());
            models.add(new RuntimeModelDescription(reference.versionId(), reference.digestAlgorithm(),
                    reference.digest(), reference.profile(), describeProperties(snapshot, keys)));
        }
        return new RuntimeDeviceSnapshotResult(devices, models);
    }

    /**
     * 从PG同一行投影读取每个精确(device,key)当前值及来源版本。
     * @param projectId 已建立普通RLS范围的可信项目
     * @param requests 已通过上游用户绑定和Schema计划过滤的请求
     * @return 按设备及属性请求顺序的稀疏状态
     */
    @Transactional(readOnly = true)
    public RuntimeDeviceCurrentResult queryCurrentValues(UUID projectId, List<RuntimeDeviceQuery> requests) {
        List<RuntimeDeviceQuery> validated = validateQueries(projectId, requests, false);
        List<PropertyRequest> properties = propertyRequests(validated);
        List<CurrentValueFact> facts = repository.currentValues(projectId, properties);
        if (facts.size() != properties.size()) {
            throw new IllegalStateException("当前值仓储未按请求组合完整返回");
        }
        Map<Integer, List<CurrentValueFact>> byDevice = new LinkedHashMap<>();
        for (CurrentValueFact fact : facts) {
            byDevice.computeIfAbsent(fact.device().position(), ignored -> new ArrayList<>()).add(fact);
        }
        List<DeviceValues> result = new ArrayList<>();
        for (int devicePosition = 0; devicePosition < validated.size(); devicePosition++) {
            RuntimeDeviceQuery request = validated.get(devicePosition);
            List<CurrentValueFact> rows = byDevice.get(devicePosition);
            if (rows == null || rows.size() != request.propertyKeys().size()) {
                throw new IllegalStateException("当前值仓储返回的设备属性位置不完整");
            }
            RuntimeDeviceAvailability device = toAvailability(request, rows.getFirst().device(), devicePosition);
            if (device.status() != RuntimeDeviceAvailability.Status.AVAILABLE) {
                result.add(new DeviceValues(request.deviceId(), device.status(), List.of()));
                continue;
            }
            ModelSnapshot snapshot = modelSnapshot(rows.getFirst(), request.expectedModelVersionId());
            List<PropertyValue> values = new ArrayList<>();
            for (int propertyPosition = 0; propertyPosition < rows.size(); propertyPosition++) {
                CurrentValueFact row = rows.get(propertyPosition);
                if (!request.propertyKeys().get(propertyPosition).equals(row.propertyKey())
                        || row.propertyPosition() != propertyPosition) {
                    throw new IllegalStateException("当前值仓储返回的属性身份或顺序漂移");
                }
                JsonNode definition = requireProperty(snapshot.root(), row.propertyKey());
                values.add(currentValue(row, request.expectedModelVersionId(), definition));
            }
            result.add(new DeviceValues(request.deviceId(), device.status(), values));
        }
        return new RuntimeDeviceCurrentResult(result);
    }

    /** 校验设备、属性和组合上限；允许空属性仅供snapshot/inspect元信息读取。 */
    private static List<RuntimeDeviceQuery> validateQueries(
            UUID projectId, List<RuntimeDeviceQuery> requests, boolean allowEmptyProperties) {
        if (projectId == null || requests == null || requests.isEmpty() || requests.size() > MAX_DEVICES) {
            throw invalid("设备请求必须包含1至20项");
        }
        Set<UUID> deviceIds = new LinkedHashSet<>();
        int combinations = 0;
        for (RuntimeDeviceQuery request : requests) {
            if (request == null || !deviceIds.add(request.deviceId())) throw invalid("设备请求不得缺失或重复");
            List<String> keys = request.propertyKeys();
            if (!allowEmptyProperties && keys.isEmpty() || keys.size() > MAX_PROPERTIES_PER_DEVICE
                    || keys.stream().anyMatch(key -> key == null || !key.matches(PROPERTY_KEY))
                    || new LinkedHashSet<>(keys).size() != keys.size()) {
                throw invalid("每台设备属性键必须满足合同且不得重复");
            }
            combinations += keys.size();
        }
        if (combinations > MAX_PROPERTY_COMBINATIONS) throw invalid("设备属性组合超过200项");
        return List.copyOf(requests);
    }

    /** 校验模型引用的封闭数量、唯一身份与稳定算法格式。 */
    private static Map<UUID, RuntimeModelReference> validateModelReferences(List<RuntimeModelReference> references) {
        if (references == null || references.size() > MAX_MODELS) throw invalid("模型引用不得超过20项");
        Map<UUID, RuntimeModelReference> result = new LinkedHashMap<>();
        for (RuntimeModelReference reference : references) {
            if (reference == null || result.putIfAbsent(reference.versionId(), reference) != null
                    || !DIGEST_ALGORITHM.equals(reference.digestAlgorithm())
                    || !reference.digest().matches("[0-9a-f]{64}") || !MODEL_PROFILE.equals(reference.profile())) {
                throw invalid("模型引用格式不合法或重复");
            }
        }
        return Map.copyOf(result);
    }

    /** 把请求映射为仓储位置事实。 */
    private static List<DeviceRequest> deviceRequests(List<RuntimeDeviceQuery> requests) {
        List<DeviceRequest> result = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            RuntimeDeviceQuery request = requests.get(index);
            result.add(new DeviceRequest(request.deviceId(), request.expectedModelVersionId(), index));
        }
        return result;
    }

    /** 展开精确组合并保留设备和属性双位置。 */
    private static List<PropertyRequest> propertyRequests(List<RuntimeDeviceQuery> requests) {
        List<PropertyRequest> result = new ArrayList<>();
        for (int device = 0; device < requests.size(); device++) {
            RuntimeDeviceQuery request = requests.get(device);
            for (int property = 0; property < request.propertyKeys().size(); property++) {
                result.add(new PropertyRequest(request.deviceId(), request.expectedModelVersionId(),
                        request.propertyKeys().get(property), device, property));
            }
        }
        return result;
    }

    /** 校验仓储返回数量和位置后映射三态设备事实。 */
    private static List<RuntimeDeviceAvailability> availability(
            List<RuntimeDeviceQuery> requests, List<DeviceFact> facts) {
        if (facts.size() != requests.size()) throw new IllegalStateException("设备仓储返回数量与请求不一致");
        List<RuntimeDeviceAvailability> result = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            result.add(toAvailability(requests.get(index), facts.get(index), index));
        }
        return List.copyOf(result);
    }

    /** 状态顺序先不可见、再模型失配，只有精确匹配才公开设备元信息。 */
    private static RuntimeDeviceAvailability toAvailability(
            RuntimeDeviceQuery request, DeviceFact fact, int expectedPosition) {
        if (!request.deviceId().equals(fact.deviceId()) || fact.position() != expectedPosition) {
            throw new IllegalStateException("设备仓储返回身份漂移");
        }
        if (!fact.visible()) {
            return new RuntimeDeviceAvailability(request.deviceId(), RuntimeDeviceAvailability.Status.NOT_AVAILABLE,
                    null, null, null, null);
        }
        if (!request.expectedModelVersionId().equals(fact.currentModelVersionId())) {
            return new RuntimeDeviceAvailability(request.deviceId(), RuntimeDeviceAvailability.Status.MODEL_MISMATCH,
                    fact.currentModelVersionId(), null, null, null);
        }
        return new RuntimeDeviceAvailability(request.deviceId(), RuntimeDeviceAvailability.Status.AVAILABLE,
                fact.currentModelVersionId(), fact.name(), fact.status(), fact.lastOnlineAt());
    }

    /** 从快照行解析并复核持久摘要。 */
    private ModelSnapshot modelSnapshot(DeviceModelFact fact, UUID expectedVersion) {
        return modelSnapshot(fact.modelSnapshot(), fact.digestAlgorithm(), fact.storedDigest(),
                fact.calculatedDigest(), fact.profile(), expectedVersion);
    }

    /** 从当前值行解析并复核持久摘要。 */
    private ModelSnapshot modelSnapshot(CurrentValueFact fact, UUID expectedVersion) {
        return modelSnapshot(fact.modelSnapshot(), fact.digestAlgorithm(), fact.storedDigest(),
                fact.calculatedDigest(), fact.profile(), expectedVersion);
    }

    /** 模型匹配时版本正文和摘要必须齐全，否则属于持久完整性故障。 */
    private ModelSnapshot modelSnapshot(String snapshotText, String algorithm, String storedDigest,
                                        String calculatedDigest, String profile, UUID versionId) {
        if (snapshotText == null || !DIGEST_ALGORITHM.equals(algorithm) || !MODEL_PROFILE.equals(profile)
                || storedDigest == null || !storedDigest.equals(calculatedDigest)) {
            throw new IllegalStateException("设备当前模型版本正文或摘要不完整");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(snapshotText);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("物模型版本正文不是合法JSON", exception);
        }
        if (root == null || !root.isObject() || !root.path("properties").isObject()) {
            throw new IllegalStateException("物模型版本正文缺少properties对象");
        }
        return new ModelSnapshot(versionId, algorithm, storedDigest, profile, root);
    }

    /** 请求声明必须与PG权威模型身份逐字段一致。 */
    private static void requireReference(RuntimeModelReference reference, ModelSnapshot snapshot) {
        if (!reference.versionId().equals(snapshot.versionId())
                || !reference.digestAlgorithm().equals(snapshot.digestAlgorithm())
                || !reference.digest().equals(snapshot.digest()) || !reference.profile().equals(snapshot.profile())) {
            throw invalid("模型摘要或Profile与权威版本不一致");
        }
    }

    /** 从不可变快照按首次请求顺序投影属性描述。 */
    private static List<RuntimePropertyDescription> describeProperties(
            ModelSnapshot snapshot, LinkedHashSet<String> keys) {
        List<RuntimePropertyDescription> result = new ArrayList<>();
        for (String key : keys) {
            JsonNode definition = requireProperty(snapshot.root(), key);
            String type = requiredText(definition, "dataType");
            requireDataType(type);
            String unit = optionalText(definition, "unit");
            java.math.BigDecimal minimum = optionalDecimal(definition, "minimum");
            java.math.BigDecimal maximum = optionalDecimal(definition, "maximum");
            List<String> options = "ENUM".equals(type) ? enumOptions(definition) : null;
            result.add(new RuntimePropertyDescription(key, type, unit, minimum, maximum, options,
                    optionalText(definition, "onLabel"), optionalText(definition, "offLabel")));
        }
        return List.copyOf(result);
    }

    /** 解析当前值状态；来源版本先于时间和类型，避免用坏值掩盖版本失配。 */
    private PropertyValue currentValue(CurrentValueFact fact, UUID expectedVersion, JsonNode definition) {
        if (!fact.valuePresent()) return new PropertyValue(fact.propertyKey(), State.NO_VALUE, null, null, null);
        if (fact.reportedModelVersionText() == null) {
            return new PropertyValue(fact.propertyKey(), State.SOURCE_VERSION_UNKNOWN, null, null, null);
        }
        UUID source;
        try {
            source = UUID.fromString(fact.reportedModelVersionText());
        } catch (IllegalArgumentException exception) {
            return new PropertyValue(fact.propertyKey(), State.CONTRACT_MISMATCH, null, null, null);
        }
        if (!expectedVersion.equals(source)) {
            return new PropertyValue(fact.propertyKey(), State.SOURCE_MODEL_MISMATCH, null, null, null);
        }
        JsonNode value;
        Instant occurredAt;
        try {
            value = objectMapper.readTree(fact.valueJson());
            occurredAt = Instant.parse(fact.occurredAtText());
        } catch (RuntimeException exception) {
            return new PropertyValue(fact.propertyKey(), State.CONTRACT_MISMATCH, null, null, null);
        }
        if (!matchesDefinition(definition, value)) {
            return new PropertyValue(fact.propertyKey(), State.CONTRACT_MISMATCH, null, null, null);
        }
        return new PropertyValue(fact.propertyKey(), State.VALUE, value, occurredAt, source);
    }

    /** 标量按冻结类型判定，OBJECT/LIST再执行同一复合Profile实例校验。 */
    private boolean matchesDefinition(JsonNode definition, JsonNode value) {
        String type;
        try {
            type = requiredText(definition, "dataType");
            requireDataType(type);
        } catch (IllegalStateException exception) {
            throw exception;
        }
        if (value == null || value.isNull()) return false;
        return switch (type) {
            case "NUMBER" -> numberMatches(definition, value);
            case "TEXT" -> value.isString();
            case "SWITCH" -> value.isBoolean();
            case "ENUM" -> value.isString() && enumOptions(definition).contains(value.asString());
            case "OBJECT", "LIST" -> compositeMatches(definition, value, type);
            default -> throw new IllegalStateException("物模型包含未知dataType");
        };
    }

    /** NUMBER沿用不可变版本中的有限值与量程合同，不能把越界上报误报为VALUE。 */
    private static boolean numberMatches(JsonNode definition, JsonNode value) {
        if (!value.isNumber()) return false;
        // 精确十进制仍须满足宿主有限数值约束；1e1000不能因BigDecimal可表示而越过五态合同。
        java.math.BigDecimal candidate;
        try {
            if (!Double.isFinite(value.doubleValue())) return false;
            candidate = value.decimalValue();
        } catch (RuntimeException exception) {
            return false;
        }
        java.math.BigDecimal minimum = optionalDecimal(definition, "minimum");
        java.math.BigDecimal maximum = optionalDecimal(definition, "maximum");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalStateException("物模型NUMBER量程上下界倒置");
        }
        return (minimum == null || candidate.compareTo(minimum) >= 0)
                && (maximum == null || candidate.compareTo(maximum) <= 0);
    }

    /** 定义损坏为内部错误，只有合法定义下实例不匹配才映射CONTRACT_MISMATCH。 */
    private boolean compositeMatches(JsonNode definition, JsonNode value, String type) {
        JsonNode schema = definition.get("schema");
        if (schema == null || !schema.isObject()) throw new IllegalStateException("复合属性缺少Schema");
        try {
            schemaValidator.validateCompositeDefinition(schema.toString(), "OBJECT".equals(type) ? "object" : "array");
        } catch (InvalidThingModelSchemaException exception) {
            throw new IllegalStateException("持久复合属性Schema损坏", exception);
        }
        try {
            schemaValidator.validateInstance(schema.toString(), value,
                    ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
            return true;
        } catch (InvalidThingModelSchemaException exception) {
            return false;
        }
    }

    /** 取得精确顶层属性，未知键在设备可用且模型匹配之后拒绝整请求。 */
    private static JsonNode requireProperty(JsonNode model, String propertyKey) {
        JsonNode property = model.path("properties").get(propertyKey);
        if (property == null) throw invalid("请求属性不在精确物模型版本中");
        if (!property.isObject()) throw new IllegalStateException("物模型属性定义不是对象");
        return property;
    }

    /** 读取必填字符串字段。 */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalStateException("物模型属性缺少字符串字段" + field);
        return value.asString();
    }

    /** 可选字符串缺失或显式JSON null时返回空，其他基础类型属于持久损坏。 */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalStateException("物模型属性字段类型错误：" + field);
        return value.asString();
    }

    /** 可选十进制数缺失或显式JSON null时返回空。 */
    private static java.math.BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalStateException("物模型属性数值字段类型错误：" + field);
        return value.decimalValue();
    }

    /** ENUM选项兼容不可变快照既有字段名enum，并拒绝空项或重复项。 */
    private static List<String> enumOptions(JsonNode definition) {
        JsonNode values = definition.get("enum");
        if (values == null) values = definition.get("enumOptions");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("ENUM属性缺少非空选项");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isString() || value.asString().isEmpty() || result.contains(value.asString())) {
                throw new IllegalStateException("ENUM属性选项不合法或重复");
            }
            result.add(value.asString());
        }
        return List.copyOf(result);
    }

    /** 物模型顶层类型闭集。 */
    private static void requireDataType(String type) {
        if (!Set.of("NUMBER", "TEXT", "SWITCH", "ENUM", "OBJECT", "LIST").contains(type)) {
            throw new IllegalStateException("物模型包含未知dataType");
        }
    }

    /** @param message 固定内部诊断，不包含资源存在性细节 @return 10001参数错误 */
    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, message);
    }

    /** @param versionId 版本 @param digestAlgorithm 算法 @param digest 摘要 @param profile Profile @param root 完整快照 */
    private record ModelSnapshot(UUID versionId, String digestAlgorithm, String digest,
                                 String profile, JsonNode root) { }
}
