package com.things.link.device.application;

import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceCurrentValueCache.ValueKey;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.device.domain.DeviceShadowSnapshot;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 批量设备当前值应用服务，统一完成项目授权、Redis 命中及 PostgreSQL 回源。 */
@Service
public class DeviceCurrentValueService {
    /** Redis 可用性不属于 API 正确性的前置条件，故障只降级并记录。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceCurrentValueService.class);
    /** 单请求设备上限，限制笛卡尔积和回源 SQL 规模。 */
    private static final int MAX_DEVICES = 100;
    /** 单请求属性上限，与物模型属性键校验共同约束 Redis MGET。 */
    private static final int MAX_PROPERTIES = 50;
    /** 属性键规则与摄入信封保持一致，避免构造无界缓存键。 */
    private static final String PROPERTY_KEY_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** 项目成员授权入口。 */
    private final ProjectService projectService;
    /** PostgreSQL 影子事实仓储。 */
    private final DeviceShadowRepository shadowRepository;
    /** Redis 热影子端口。 */
    private final DeviceCurrentValueCache cache;
    /** PostgreSQL jsonb 投影解析器。 */
    private final ObjectMapper objectMapper;
    /** 仅当前值JSON文本读取保留小数精度，不修改全局mapper或默认整数类型。 */
    private final ObjectReader currentValueReader;

    /**
     * @param projectService 项目授权服务
     * @param shadowRepository 影子仓储
     * @param cache Redis 热影子
     * @param objectMapper JSON 解析器
     */
    public DeviceCurrentValueService(ProjectService projectService,
                                     DeviceShadowRepository shadowRepository, DeviceCurrentValueCache cache,
                                     ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.shadowRepository = shadowRepository;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.currentValueReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * 查询多设备与多属性的当前值笛卡尔积，缺少当前值的组合不会出现在结果中。
     *
     * @param projectId 项目 ID
     * @param deviceIds 设备 ID
     * @param propertyKeys 属性键
     * @return 按请求设备、属性顺序排列的已有当前值
     */
    @Transactional(readOnly = true)
    public List<DeviceCurrentValue> findAll(UUID projectId, Collection<UUID> deviceIds,
                                            Collection<String> propertyKeys) {
        projectService.requireRoleInProject(projectId);
        return findAllTrusted(projectId, deviceIds, propertyKeys);
    }

    /**
     * 无成员校验的受信核心：跳过 {@link #findAll} 的 {@code requireRoleInProject}，其余逻辑相同。
     *
     * <p>这是 App 数据面（S11-2b）跨模块端口：enduser 先经 {@code app_user_device} 校验设备绑定，
     * 再调本方法，因此本方法不做控制台账号成员鉴权 —— App 请求没有 {@code accountId}。项目隔离仍由
     * App 安全链写入的 {@code RlsScopeContext} 驱动 RLS 兜底，SQL 里也继续显式带 {@code project_id}。</p>
     *
     * @param projectId 项目 ID
     * @param deviceIds 设备 ID
     * @param propertyKeys 属性键
     * @return 按请求设备、属性顺序排列的已有当前值
     */
    @Transactional(readOnly = true)
    public List<DeviceCurrentValue> findAllTrusted(UUID projectId, Collection<UUID> deviceIds,
                                                   Collection<String> propertyKeys) {
        List<UUID> devices = distinctDevices(deviceIds);
        List<String> properties = distinctProperties(propertyKeys);
        List<ValueKey> requestedKeys = new ArrayList<>();
        for (UUID deviceId : devices) {
            for (String propertyKey : properties) {
                requestedKeys.add(new ValueKey(deviceId, propertyKey));
            }
        }
        Map<ValueKey, DeviceCurrentValue> values = readCache(projectId, requestedKeys);
        // 先取缓存候选再读取PG资格水位；查询失败不得降级为旧缓存。
        Map<ValueKey, String> revisions = new LinkedHashMap<>();
        for (var snapshot : shadowRepository.findReportedRevisions(projectId, devices)) {
            JsonNode metadata = parseObject(snapshot.reportedRevisions());
            for (String property : properties) {
                String revision = reportedRevision(metadata, property);
                if (revision != null) revisions.put(new ValueKey(snapshot.deviceId(), property), revision);
            }
        }
        values.entrySet().removeIf(entry -> entry.getValue().reportedRevision() == null
                || !entry.getValue().reportedRevision().equals(revisions.get(entry.getKey())));
        Set<ValueKey> misses = new LinkedHashSet<>(requestedKeys);
        misses.removeAll(values.keySet());
        if (!misses.isEmpty()) {
            values.putAll(readDatabaseAndBackfill(projectId, misses));
        }
        return requestedKeys.stream().map(values::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Redis 故障等同全量未命中，调用方继续使用 PostgreSQL 事实源。 */
    private Map<ValueKey, DeviceCurrentValue> readCache(UUID projectId, List<ValueKey> keys) {
        try {
            return new LinkedHashMap<>(cache.findAll(projectId, keys));
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis 热影子批量读取失败，回源 PostgreSQL: projectId={}", projectId, exception);
            return new LinkedHashMap<>();
        }
    }

    /** 一次批量 SQL 回源缺失设备，并携带各属性实际接受序号回填 Redis。 */
    private Map<ValueKey, DeviceCurrentValue> readDatabaseAndBackfill(UUID projectId, Set<ValueKey> misses) {
        Set<UUID> missingDevices = new LinkedHashSet<>();
        misses.forEach(key -> missingDevices.add(key.deviceId()));
        Map<ValueKey, DeviceCurrentValue> result = new LinkedHashMap<>();
        for (DeviceShadowSnapshot snapshot : shadowRepository.findSnapshots(projectId, missingDevices)) {
            JsonNode reported = parseObject(snapshot.reported());
            JsonNode reportedAt = parseObject(snapshot.reportedAt());
            JsonNode reportedRevisions = parseObject(snapshot.reportedRevisions());
            JsonNode modelVersions = parseObject(snapshot.reportedModelVersion());
            for (ValueKey key : misses) {
                if (!key.deviceId().equals(snapshot.deviceId()) || !reported.has(key.propertyKey())
                        || !reportedAt.has(key.propertyKey())) {
                    continue;
                }
                DeviceCurrentValue value = new DeviceCurrentValue(snapshot.deviceId(), key.propertyKey(),
                        reported.get(key.propertyKey()), Instant.parse(reportedAt.get(key.propertyKey()).asString()),
                        snapshot.version(), reportedRevision(reportedRevisions, key.propertyKey()),
                        modelVersion(modelVersions, key.propertyKey()));
                result.put(key, value);
                backfill(projectId, value);
            }
        }
        return result;
    }

    /** 单属性回填复用按事件时间合并，避免查询线程覆盖并发上行的新缓存值。 */
    private void backfill(UUID projectId, DeviceCurrentValue value) {
        if (value.reportedRevision() == null) return;
        try {
            cache.merge(projectId, value.deviceId(), Map.of(value.propertyKey(),
                    new DeviceCurrentValueCache.ReportedValue(objectMapper.writeValueAsString(value.value()),
                            value.occurredAt(), value.shadowVersion(), value.reportedRevision(), value.thingModelVersionId())));
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis 热影子回填失败，不影响 PostgreSQL 查询结果: projectId={}, deviceId={}",
                    projectId, value.deviceId(), exception);
        }
    }

    /** 缺键代表历史未知，非文本或非法序号属于PG数据损坏。 */
    private static String reportedRevision(JsonNode map, String key) {
        JsonNode value = map.get(key);
        if (value == null) return null;
        if (!value.isString()) throw new IllegalStateException("PG属性接受序号不是文本");
        return com.things.link.shared.message.ReportedRevision.require(value.asString());
    }

    /** 来源来自属性写入记录，不读取设备当前绑定来填补历史。 */
    private static UUID modelVersion(JsonNode map, String key) {
        JsonNode value = map.get(key);
        if (value == null) return null;
        if (!value.isString()) throw new IllegalStateException("PG属性模型来源不是文本");
        return UUID.fromString(value.asString());
    }

    /** 将空 jsonb 统一为对象；数据库约束保证非对象异常属于内部数据损坏。 */
    private JsonNode parseObject(String json) {
        JsonNode node = json == null ? objectMapper.createObjectNode() : currentValueReader.readValue(json);
        if (!node.isObject()) {
            throw new IllegalStateException("dev_shadow 当前值投影不是 JSON 对象");
        }
        return node;
    }

    /** 去重并限制设备规模，避免重复设备放大 Redis 与 SQL 工作量。 */
    private static List<UUID> distinctDevices(Collection<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty() || deviceIds.size() > MAX_DEVICES
                || deviceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备列表必须包含 1～100 个有效 ID");
        }
        return List.copyOf(new LinkedHashSet<>(deviceIds));
    }

    /** 去重并校验属性键，确保批量接口确实表达“多设备 × 多属性”的有界组合。 */
    private static List<String> distinctProperties(Collection<String> propertyKeys) {
        if (propertyKeys == null || propertyKeys.isEmpty() || propertyKeys.size() > MAX_PROPERTIES
                || propertyKeys.stream().anyMatch(key -> key == null || !key.matches(PROPERTY_KEY_PATTERN))) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "属性列表必须包含 1～50 个有效属性键");
        }
        return List.copyOf(new LinkedHashSet<>(propertyKeys));
    }
}
