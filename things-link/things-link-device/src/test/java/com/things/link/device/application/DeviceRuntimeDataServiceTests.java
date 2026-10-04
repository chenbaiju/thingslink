package com.things.link.device.application;

import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.device.domain.DeviceRuntimeRepository;
import com.things.link.device.domain.DeviceRuntimeRepository.CurrentValueFact;
import com.things.link.device.domain.DeviceRuntimeRepository.DeviceFact;
import com.things.link.device.domain.DeviceRuntimeRepository.DeviceModelFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 设备运行快照的状态优先级、不可变模型投影和PG当前值解释测试。 */
@ExtendWith(MockitoExtension.class)
class DeviceRuntimeDataServiceTests {

    /** 稳定测试项目。 */ private final UUID projectId = UUID.randomUUID();
    /** 可见设备。 */ private final UUID availableId = UUID.randomUUID();
    /** 不可见设备。 */ private final UUID hiddenId = UUID.randomUUID();
    /** 模型失配设备。 */ private final UUID mismatchId = UUID.randomUUID();
    /** 请求精确模型。 */ private final UUID modelId = UUID.randomUUID();
    /** 其他模型。 */ private final UUID otherModelId = UUID.randomUUID();
    /** PG仓储替身。 */ @Mock private DeviceRuntimeRepository repository;
    /** 复合属性校验器替身。 */ @Mock private ThingModelSchemaValidator schemaValidator;
    /** 被测运行服务。 */ private DeviceRuntimeDataService service;

    /** 每项测试使用真实JSON映射和独立服务。 */
    @BeforeEach
    void setUp() {
        service = new DeviceRuntimeDataService(repository, JsonMapper.builder().build(), schemaValidator);
    }

    /** 设计元数据发现精确摘要并完整投影属性，不依赖浏览器先猜版本和键。 */
    @Test
    void bindingMetadataDiscoversExactModelAndAllTopLevelProperties() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, true, modelId)));
        when(repository.snapshot(eq(projectId), anyList())).thenAnswer(invocation -> {
            List<DeviceRuntimeRepository.DeviceRequest> requests = invocation.getArgument(1);
            assertThat(requests).containsExactly(new DeviceRuntimeRepository.DeviceRequest(availableId, modelId, 0));
            return List.of(new DeviceModelFact(fact(availableId, 0, true, modelId), modelSnapshot(),
                    "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1"));
        });
        RuntimeDeviceSnapshotResult result = service.bindingMetadata(projectId, availableId);
        assertThat(result.devices()).hasSize(1);
        RuntimeModelDescription model = result.models().getFirst();
        assertThat(model.versionId()).isEqualTo(modelId);
        assertThat(model.digest()).isEqualTo("a".repeat(64));
        assertThat(model.properties()).extracting(RuntimePropertyDescription::propertyKey)
                .containsExactly("temperature", "mode");
        assertThat(model.properties().getFirst().unit()).isEqualTo("℃");
        assertThat(model.properties().get(1).enumOptions()).containsExactly("AUTO", "MANUAL");
    }

    /** 发现后换模或设备被隐藏时，旧模型正文不能泄漏到设计器。 */
    @Test
    void bindingMetadataRejectsModelChangeAndRevocationBetweenReads() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, true, modelId)));
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(
                List.of(new DeviceModelFact(fact(availableId, 0, true, otherModelId), null, null, null, null, null)),
                List.of(new DeviceModelFact(fact(availableId, 0, false, null), null, null, null, null, null)));
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId)).hasMessageContaining("模型已变化");
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
    }

    /** 未绑定模型及不可见设备沿现有设备错误，不制造空模型正向。 */
    @Test
    void bindingMetadataRejectsUnboundAndInvisibleDevice() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(
                List.of(fact(availableId, 0, true, null)), List.of(fact(availableId, 0, false, null)));
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId)).hasMessageContaining("尚未关联");
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).snapshot(eq(projectId), anyList());
    }

    /** 200项设计目录完整返回，201项拒绝；不改变模型创建上限或悄悄截断。 */
    @Test
    void bindingMetadataEnforcesCompletePropertyDirectoryBudget() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, true, modelId)));
        String atLimit = modelSnapshotWithKeys(java.util.stream.IntStream.range(0, 200).mapToObj(i -> "p" + i).toList());
        String overLimit = modelSnapshotWithKeys(java.util.stream.IntStream.range(0, 201).mapToObj(i -> "p" + i).toList());
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(
                List.of(new DeviceModelFact(fact(availableId, 0, true, modelId), atLimit,
                        "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1")),
                List.of(new DeviceModelFact(fact(availableId, 0, true, modelId), overLimit,
                        "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));
        assertThat(service.bindingMetadata(projectId, availableId).models().getFirst().properties()).hasSize(200);
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId)).hasMessageContaining("200项");
    }

    /** 设计元数据与运行快照共用持久摘要完整性检查，不能为发现入口降低要求。 */
    @Test
    void bindingMetadataRejectsCorruptStoredDigest() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, true, modelId)));
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(new DeviceModelFact(
                fact(availableId, 0, true, modelId), modelSnapshot(), "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));
        assertThatThrownBy(() -> service.bindingMetadata(projectId, availableId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("摘要不完整");
    }

    /** 当前模型来自指定设备PG事实；不把客户端预期版本带入查询或做全量扫描。 */
    @Test
    void currentModelsUseOnlyBoundedDeviceFacts() {
        when(repository.inspect(eq(projectId), anyList())).thenAnswer(invocation -> {
            List<DeviceRuntimeRepository.DeviceRequest> requests = invocation.getArgument(1);
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().deviceId()).isEqualTo(availableId);
            assertThat(requests.getFirst().expectedModelVersionId()).isNull();
            return List.of(fact(availableId, 0, true, modelId));
        });
        assertThat(service.currentModelVersions(projectId, Set.of(availableId)))
                .isEqualTo(Map.of(availableId, modelId));
    }

    /** 绑定关系不代表模型仍存在；设备不可见与空当前模型均不可放行实时提示。 */
    @Test
    void currentModelsRejectMissingDeviceOrModel() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, false, null)));
        assertThatThrownBy(() -> service.currentModelVersions(projectId, Set.of(availableId)))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(availableId, 0, true, null)));
        assertThatThrownBy(() -> service.currentModelVersions(projectId, Set.of(availableId)))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);
    }

    /** 仓储遗漏或身份漂移是内部错误，不能当成空映射悄悄缩小授权。 */
    @Test
    void currentModelsRejectRepositoryDrift() {
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of());
        assertThatThrownBy(() -> service.currentModelVersions(projectId, Set.of(availableId)))
                .isInstanceOf(IllegalStateException.class);
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(fact(hiddenId, 0, true, modelId)));
        assertThatThrownBy(() -> service.currentModelVersions(projectId, Set.of(availableId)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 不可见不得带模型，模型失配只揭示当前模型，成功才返回设备元信息。 */
    @Test
    void inspectPreservesRequestOrderAndStatusDisclosureBoundary() {
        List<RuntimeDeviceQuery> requests = List.of(query(hiddenId), query(mismatchId), query(availableId));
        when(repository.inspect(eq(projectId), anyList())).thenReturn(List.of(
                fact(hiddenId, 0, false, null), fact(mismatchId, 1, true, otherModelId),
                fact(availableId, 2, true, modelId)));

        List<RuntimeDeviceAvailability> result = service.inspect(projectId, requests);

        assertThat(result).extracting(RuntimeDeviceAvailability::status).containsExactly(
                RuntimeDeviceAvailability.Status.NOT_AVAILABLE,
                RuntimeDeviceAvailability.Status.MODEL_MISMATCH,
                RuntimeDeviceAvailability.Status.AVAILABLE);
        assertThat(result.get(0).currentModelVersionId()).isNull();
        assertThat(result.get(1).currentModelVersionId()).isEqualTo(otherModelId);
        assertThat(result.get(2).name()).isEqualTo("泵站设备");
    }

    /** 只为成功设备返回实际模型，属性按请求首次出现顺序投影且不读取可变定义。 */
    @Test
    void snapshotsReturnOnlyModelsUsedByAvailableDevices() {
        String model = modelSnapshot();
        List<RuntimeDeviceQuery> requests = List.of(
                new RuntimeDeviceQuery(availableId, modelId, List.of("temperature", "mode")),
                new RuntimeDeviceQuery(hiddenId, otherModelId, List.of("secret")));
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(
                new DeviceModelFact(fact(availableId, 0, true, modelId), model,
                        "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64), "a".repeat(64),
                        "TC_PROPERTY_COMPOSITE_V1"),
                new DeviceModelFact(fact(hiddenId, 1, false, null), null, null, null, null, null)));

        RuntimeDeviceSnapshotResult result = service.querySnapshots(projectId, requests, List.of(
                reference(modelId, "a"), reference(otherModelId, "b")));

        assertThat(result.devices()).extracting(RuntimeDeviceAvailability::status).containsExactly(
                RuntimeDeviceAvailability.Status.AVAILABLE, RuntimeDeviceAvailability.Status.NOT_AVAILABLE);
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().getFirst().versionId()).isEqualTo(modelId);
        assertThat(result.models().getFirst().properties())
                .extracting(RuntimePropertyDescription::propertyKey).containsExactly("temperature", "mode");
        assertThat(result.models().getFirst().properties().get(1).enumOptions()).containsExactly("AUTO", "MANUAL");
    }

    /** 模型匹配后未知顶层键拒绝整请求，不能伪装成设备不可见。 */
    @Test
    void snapshotsRejectUnknownPropertyOnlyAfterSuccessfulDeviceMatch() {
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(new DeviceModelFact(
                fact(availableId, 0, true, modelId), modelSnapshot(), "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));

        assertThatThrownBy(() -> service.querySnapshots(projectId,
                List.of(new RuntimeDeviceQuery(availableId, modelId, List.of("missing"))),
                List.of(reference(modelId, "a"))))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class)
                .hasMessageContaining("请求属性不在精确物模型版本中");
    }

    /** PG值状态完整区分无值、未知来源、来源失配、合同失配与有效值。 */
    @Test
    void currentValuesExposeAllSparsePropertyStatesWithoutRedisSemantics() {
        List<String> keys = List.of("temperature", "empty", "unknown", "old", "broken");
        List<RuntimeDeviceQuery> requests = List.of(new RuntimeDeviceQuery(availableId, modelId, keys));
        DeviceFact device = fact(availableId, 0, true, modelId);
        String model = modelSnapshotWithKeys(keys);
        when(repository.currentValues(eq(projectId), anyList())).thenReturn(List.of(
                current(device, "temperature", 0, model, true, "21.5", "2026-09-07T01:00:00Z", modelId.toString()),
                current(device, "empty", 1, model, false, null, null, null),
                current(device, "unknown", 2, model, true, "1", "2026-09-07T01:00:00Z", null),
                current(device, "old", 3, model, true, "1", "2026-09-07T01:00:00Z", otherModelId.toString()),
                current(device, "broken", 4, model, true, "\"not-number\"", "2026-09-07T01:00:00Z", modelId.toString())));

        RuntimeDeviceCurrentResult result = service.queryCurrentValues(projectId, requests);

        assertThat(result.devices().getFirst().values()).extracting(RuntimeDeviceCurrentResult.PropertyValue::state)
                .containsExactly(RuntimeDeviceCurrentResult.State.VALUE, RuntimeDeviceCurrentResult.State.NO_VALUE,
                        RuntimeDeviceCurrentResult.State.SOURCE_VERSION_UNKNOWN,
                        RuntimeDeviceCurrentResult.State.SOURCE_MODEL_MISMATCH,
                        RuntimeDeviceCurrentResult.State.CONTRACT_MISMATCH);
        assertThat(result.devices().getFirst().values().getFirst().reportedModelVersionId()).isEqualTo(modelId);
        assertThat(result.devices().getFirst().values().subList(1, 5))
                .allMatch(value -> value.value() == null);
    }

    /** NUMBER值越过不可变版本上界时必须返回合同失配，不能只凭JSON数值类型判成VALUE。 */
    @Test
    void currentValuesApplyPublishedNumberRange() {
        DeviceFact device = fact(availableId, 0, true, modelId);
        String model = """
                {"properties":{"temperature":{"dataType":"NUMBER","minimum":-50,"maximum":150}},
                "events":{},"commands":{}}
                """;
        when(repository.currentValues(eq(projectId), anyList())).thenReturn(List.of(current(
                device, "temperature", 0, model, true, "151", "2026-09-07T01:00:00Z", modelId.toString())));

        RuntimeDeviceCurrentResult result = service.queryCurrentValues(projectId, List.of(
                new RuntimeDeviceQuery(availableId, modelId, List.of("temperature"))));

        assertThat(result.devices().getFirst().values().getFirst().state())
                .isEqualTo(RuntimeDeviceCurrentResult.State.CONTRACT_MISMATCH);
    }

    /** 无量程NUMBER仍受运行数值有限性约束，BigDecimal可表示不等于宿主double可安全解释。 */
    @Test
    void currentValuesRejectNonFiniteHostNumberWithoutRange() {
        DeviceFact device = fact(availableId, 0, true, modelId);
        when(repository.currentValues(eq(projectId), anyList())).thenReturn(List.of(current(
                device, "temperature", 0, modelSnapshot(), true, "1e1000",
                "2026-09-07T01:00:00Z", modelId.toString())));

        RuntimeDeviceCurrentResult result = service.queryCurrentValues(projectId, List.of(
                new RuntimeDeviceQuery(availableId, modelId, List.of("temperature"))));

        assertThat(result.devices().getFirst().values().getFirst().state())
                .isEqualTo(RuntimeDeviceCurrentResult.State.CONTRACT_MISMATCH);
    }

    /** PG精确正文经过服务后序列化仍必须保持嵌套大整数及小数，不只验证存储端。 */
    @Test
    void preservesDatabaseCompositeDecimalsInRuntimeResponse() {
        String model = "{\"properties\":{\"payload\":{\"dataType\":\"OBJECT\",\"schema\":{\"type\":\"object\"}}}}";
        String value = "{\"big\":9007199254740993.123456789,\"integer\":9007199254740993123456789,"
                + "\"list\":[9007199254740993.123456789]}";
        when(repository.currentValues(eq(projectId), anyList())).thenReturn(List.of(current(
                fact(availableId, 0, true, modelId), "payload", 0, model, true, value,
                "2026-09-07T01:00:00Z", modelId.toString())));
        var result = service.queryCurrentValues(projectId, List.of(
                new RuntimeDeviceQuery(availableId, modelId, List.of("payload"))));
        var property = result.devices().getFirst().values().getFirst();
        assertThat(property.state()).isEqualTo(RuntimeDeviceCurrentResult.State.VALUE);
        assertThat(property.value().path("big").decimalValue()).isEqualByComparingTo("9007199254740993.123456789");
        assertThat(property.value().path("integer").bigIntegerValue())
                .isEqualTo(new java.math.BigInteger("9007199254740993123456789"));
        assertThat(property.value().path("list").get(0).decimalValue()).isEqualByComparingTo("9007199254740993.123456789");
        var response = com.things.link.device.api.dto.response.DeviceRuntimeCurrentValuesResponse.from(result);
        assertThat(JsonMapper.builder().build().writeValueAsString(response)).contains("9007199254740993.123456789");
    }

    /** 模型边界读入也须精确；只修当前值会使合法临界值被错误拒绝。 */
    @Test
    void preservesDatabaseModelRangeBeforeSnapshotProjection() {
        String model = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\","
                + "\"minimum\":0.123456789012345678,\"maximum\":0.123456789012345679}}}";
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(new DeviceModelFact(
                fact(availableId, 0, true, modelId), model, "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));
        var result = service.querySnapshots(projectId,
                List.of(new RuntimeDeviceQuery(availableId, modelId, List.of("temperature"))),
                List.of(reference(modelId, "a")));
        var property = result.models().getFirst().properties().getFirst();
        assertThat(property.minimumValue()).isEqualByComparingTo("0.123456789012345678");
        assertThat(property.maximumValue()).isEqualByComparingTo("0.123456789012345679");
    }

    /** 发布管线允许可选元数据显式null；运行描述应保留JSON null而不是误判持久损坏。 */
    @Test
    void snapshotsAcceptExplicitNullOptionalMetadata() {
        String model = """
                {"properties":{"temperature":{"dataType":"NUMBER","unit":null,"minimum":null,"maximum":null,
                "onLabel":null,"offLabel":null}},"events":{},"commands":{}}
                """;
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(new DeviceModelFact(
                fact(availableId, 0, true, modelId), model, "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));

        RuntimePropertyDescription property = service.querySnapshots(projectId,
                List.of(new RuntimeDeviceQuery(availableId, modelId, List.of("temperature"))),
                List.of(reference(modelId, "a"))).models().getFirst().properties().getFirst();

        assertThat(property.unit()).isNull();
        assertThat(property.minimumValue()).isNull();
        assertThat(property.maximumValue()).isNull();
        assertThat(property.onLabel()).isNull();
        assertThat(property.offLabel()).isNull();
    }

    /** 摘要复算不一致是持久完整性故障，不能降格为业务参数错误。 */
    @Test
    void snapshotDigestMismatchPreservesInternalIntegrityFailure() {
        when(repository.snapshot(eq(projectId), anyList())).thenReturn(List.of(new DeviceModelFact(
                fact(availableId, 0, true, modelId), modelSnapshot(), "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1")));

        assertThatThrownBy(() -> service.querySnapshots(projectId, List.of(query(availableId)),
                List.of(reference(modelId, "a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要不完整");
    }

    /** 非VALUE状态即使只夹带来源版本也必须拒绝，避免HTTP选择性省略掩盖领域事实漂移。 */
    @Test
    void nonValueResultRejectsLoneReportedModelVersion() {
        assertThatThrownBy(() -> new RuntimeDeviceCurrentResult.PropertyValue(
                "temperature", RuntimeDeviceCurrentResult.State.NO_VALUE, null, null, modelId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态与事实字段不一致");
    }

    /** 构造无属性的设备检查请求。 */
    private RuntimeDeviceQuery query(UUID deviceId) {
        return new RuntimeDeviceQuery(deviceId, modelId, List.of());
    }

    /** 构造仓储设备事实。 */
    private DeviceFact fact(UUID deviceId, int position, boolean visible, UUID currentModel) {
        return new DeviceFact(deviceId, position, visible, currentModel,
                visible ? "泵站设备" : null, visible ? "ONLINE" : null,
                visible ? Instant.parse("2026-09-07T00:00:00Z") : null);
    }

    /** 构造请求模型身份。 */
    private static RuntimeModelReference reference(UUID versionId, String digestPrefix) {
        return new RuntimeModelReference(versionId, "PG_JSONB_TEXT_V1_SHA256", digestPrefix.repeat(64),
                "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 两个典型标量属性的不可变模型快照。 */
    private static String modelSnapshot() {
        return """
                {"properties":{"temperature":{"dataType":"NUMBER","unit":"℃","minimum":-40,"maximum":125},
                "mode":{"dataType":"ENUM","enum":["AUTO","MANUAL"]}},"events":{},"commands":{}}
                """;
    }

    /** 为当前值状态测试生成相同NUMBER属性集合。 */
    private static String modelSnapshotWithKeys(List<String> keys) {
        String properties = keys.stream().map(key -> "\"" + key + "\":{\"dataType\":\"NUMBER\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"properties\":{" + properties + "},\"events\":{},\"commands\":{}}";
    }

    /** 构造同模型摘要的当前值仓储行。 */
    private static CurrentValueFact current(DeviceFact device, String key, int position, String model,
                                            boolean present, String value, String occurredAt, String source) {
        return new CurrentValueFact(device, key, position, model, "PG_JSONB_TEXT_V1_SHA256",
                "a".repeat(64), "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1",
                present, value, occurredAt, source);
    }
}
