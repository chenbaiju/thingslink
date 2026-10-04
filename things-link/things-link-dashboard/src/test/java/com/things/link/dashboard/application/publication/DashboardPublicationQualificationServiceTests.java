package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.device.application.ThingModelPropertyFacts;
import com.things.link.device.application.ThingModelPropertyFactsPort;
import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 看板发布外部资格的完整成功、失败关闭、短路顺序和值证明边界测试。 */
@DisplayName("看板发布外部资格")
class DashboardPublicationQualificationServiceTests {

    /** 构造候选最小规范Schema的JSON映射器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 固定候选项目。 */
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    /** 固定模型版本。 */
    private static final UUID MODEL_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    /** 固定默认设备。 */
    private static final UUID DEVICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    /** 固定模型摘要。 */
    private static final String MODEL_DIGEST = "a".repeat(64);
    /** 固定Schema摘要。 */
    private static final String SCHEMA_DIGEST = "b".repeat(64);
    /** 固定内置资源摘要。 */
    private static final String RESOURCE_DIGEST = "c".repeat(64);

    /** 八类需求全部通过时按稳定顺序核验，并产生仍未发布的不可伪造资格值。 */
    @Test
    @DisplayName("八类外部需求全部通过后返回资格证明")
    void allEightRequirementTypesProduceQualifiedCandidate() {
        QualificationFixture fixture = qualifiedFixture();
        DashboardPublicationCandidate candidate = candidate(allRequirements());

        QualifiedDashboardPublicationCandidate qualified = fixture.service.qualify(candidate);

        assertThat(qualified.candidate()).isSameAs(candidate);
        org.mockito.InOrder order = inOrder(
                fixture.host, fixture.models, fixture.devices, fixture.properties, fixture.adapters);
        order.verify(fixture.host).current();
        order.verify(fixture.models).find(PROJECT_ID, MODEL_VERSION_ID);
        order.verify(fixture.devices).find(PROJECT_ID, DEVICE_ID);
        order.verify(fixture.properties).find(PROJECT_ID, MODEL_VERSION_ID, "temperature");
        order.verify(fixture.adapters).supportsHistory(PROJECT_ID, historicalRequirement());
        order.verify(fixture.adapters).supports(PROJECT_ID, adapterRequirement(
                DashboardPublicationEligibilityRequirement.AdapterCapability.CURRENT_VALUE));
        order.verifyNoMoreInteractions();
    }

    /** 模型不存在、错项目、错ID、算法、摘要或Profile任一不匹配均使用同一安全原因。 */
    @Test
    @DisplayName("模型版本身份和摘要错配统一失败关闭")
    void modelIdentityAndDigestMismatchesUseOneSafeFailure() {
        DashboardPublicationCandidate candidate = candidate(List.of(modelRequirement()));
        List<Optional<ThingModelVersionDescriptor>> invalidFacts = List.of(
                Optional.empty(),
                Optional.of(modelDescriptor(UUID.randomUUID(), MODEL_VERSION_ID,
                        "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1")),
                Optional.of(modelDescriptor(PROJECT_ID, UUID.randomUUID(),
                        "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1")),
                Optional.of(modelDescriptor(PROJECT_ID, MODEL_VERSION_ID,
                        "SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1")),
                Optional.of(modelDescriptor(PROJECT_ID, MODEL_VERSION_ID,
                        "PG_JSONB_TEXT_V1_SHA256", "d".repeat(64), "TC_PROPERTY_COMPOSITE_V1")),
                Optional.of(modelDescriptor(PROJECT_ID, MODEL_VERSION_ID,
                        "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "OTHER_PROFILE")));

        for (Optional<ThingModelVersionDescriptor> facts : invalidFacts) {
            QualificationFixture fixture = fixture();
            when(fixture.host.current()).thenReturn(Optional.of(hostDescriptor()));
            when(fixture.models.find(PROJECT_ID, MODEL_VERSION_ID)).thenReturn(facts);

            assertSafeFailure(fixture.service, candidate,
                    DashboardPublicationQualificationException.Reason.MODEL_REFERENCE_INVALID);
            verifyNoInteractions(fixture.properties, fixture.devices, fixture.adapters);
        }
    }

    /** 属性不存在、投影键漂移、dataType不在允许闭集及历史属性非NUMBER均安全拒绝。 */
    @Test
    @DisplayName("模型属性缺失或类型不兼容统一失败关闭")
    void missingAndIncompatibleModelPropertiesFailClosed() {
        List<Optional<ThingModelPropertyFacts>> invalidFacts = List.of(
                Optional.empty(),
                Optional.of(new ThingModelPropertyFacts(
                        "other", ThingModelPropertyFacts.DataType.NUMBER,
                        BigDecimal.ZERO, BigDecimal.TEN)),
                Optional.of(new ThingModelPropertyFacts(
                        "temperature", ThingModelPropertyFacts.DataType.OBJECT, null, null)));
        DashboardPublicationCandidate propertyCandidate = candidate(List.of(
                modelRequirement(), propertyRequirement()));

        for (Optional<ThingModelPropertyFacts> facts : invalidFacts) {
            QualificationFixture fixture = qualifiedFixture();
            when(fixture.properties.find(PROJECT_ID, MODEL_VERSION_ID, "temperature")).thenReturn(facts);
            assertSafeFailure(fixture.service, propertyCandidate,
                    DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
        }

        QualificationFixture historyFixture = fixture();
        when(historyFixture.host.current()).thenReturn(Optional.of(hostDescriptor()));
        when(historyFixture.models.find(PROJECT_ID, MODEL_VERSION_ID))
                .thenReturn(Optional.of(modelDescriptor()));
        when(historyFixture.properties.find(PROJECT_ID, MODEL_VERSION_ID, "temperature"))
                .thenReturn(Optional.of(new ThingModelPropertyFacts(
                        "temperature", ThingModelPropertyFacts.DataType.TEXT, null, null)));
        assertSafeFailure(historyFixture.service, candidate(List.of(
                        modelRequirement(), historicalRequirement())),
                DashboardPublicationQualificationException.Reason.MODEL_PROPERTY_INVALID);
    }

    /** MODEL量程缺边界、相等、反向或超出ConfigNumber合同时均不得取得资格。 */
    @Test
    @DisplayName("MODEL量程缺失无序或超合同均失败关闭")
    void invalidModelGaugeRangesFailClosed() {
        List<ThingModelPropertyFacts> invalidRanges = List.of(
                new ThingModelPropertyFacts("temperature", ThingModelPropertyFacts.DataType.NUMBER,
                        null, BigDecimal.TEN),
                new ThingModelPropertyFacts("temperature", ThingModelPropertyFacts.DataType.NUMBER,
                        BigDecimal.ZERO, null),
                numberProperty(BigDecimal.ONE, BigDecimal.ONE),
                numberProperty(BigDecimal.TEN, BigDecimal.ONE),
                numberProperty(new BigDecimal("-1000000000001"), BigDecimal.ZERO),
                numberProperty(BigDecimal.ZERO, new BigDecimal("0.1234567890123456")));
        DashboardPublicationCandidate candidate = candidate(List.of(
                modelRequirement(), gaugeRequirement()));

        for (ThingModelPropertyFacts property : invalidRanges) {
            QualificationFixture fixture = fixture();
            when(fixture.host.current()).thenReturn(Optional.of(hostDescriptor()));
            when(fixture.models.find(PROJECT_ID, MODEL_VERSION_ID))
                    .thenReturn(Optional.of(modelDescriptor()));
            when(fixture.properties.find(PROJECT_ID, MODEL_VERSION_ID, "temperature"))
                    .thenReturn(Optional.of(property));
            assertSafeFailure(fixture.service, candidate,
                    DashboardPublicationQualificationException.Reason.MODEL_RANGE_INVALID);
        }
    }

    /** 默认设备不存在、跨项目、ID漂移或当前模型不符均使用同一安全原因。 */
    @Test
    @DisplayName("默认设备身份归属和当前模型错配统一失败关闭")
    void invalidDefaultDevicesUseOneSafeFailure() {
        DashboardPublicationCandidate candidate = candidate(List.of(
                modelRequirement(), defaultDeviceRequirement()));
        List<Optional<DeviceModelBindingFacts>> invalidFacts = List.of(
                Optional.empty(),
                Optional.of(new DeviceModelBindingFacts(DEVICE_ID, UUID.randomUUID(), MODEL_VERSION_ID)),
                Optional.of(new DeviceModelBindingFacts(UUID.randomUUID(), PROJECT_ID, MODEL_VERSION_ID)),
                Optional.of(new DeviceModelBindingFacts(DEVICE_ID, PROJECT_ID, UUID.randomUUID())));

        for (Optional<DeviceModelBindingFacts> facts : invalidFacts) {
            QualificationFixture fixture = qualifiedFixture();
            when(fixture.devices.find(PROJECT_ID, DEVICE_ID)).thenReturn(facts);
            assertSafeFailure(fixture.service, candidate,
                    DashboardPublicationQualificationException.Reason.DEFAULT_DEVICE_INVALID);
            verifyNoInteractions(fixture.adapters);
        }
    }

    /** 八种数据适配能力逐种缺失时都必须拒绝，不能由另一种能力替代。 */
    @ParameterizedTest
    @EnumSource(DashboardPublicationEligibilityRequirement.AdapterCapability.class)
    @DisplayName("每种数据适配能力缺失都独立失败关闭")
    void everyMissingDataAdapterCapabilityFailsClosed(
            DashboardPublicationEligibilityRequirement.AdapterCapability capability) {
        QualificationFixture fixture = qualifiedFixture();
        DashboardPublicationEligibilityRequirement.DataAdapter adapter = adapterRequirement(capability);
        when(fixture.adapters.supports(PROJECT_ID, adapter)).thenReturn(false);

        assertSafeFailure(fixture.service, candidate(List.of(modelRequirement(), adapter)),
                DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE);
    }

    /** 宿主描述缺失及格式、版本、Schema、组件、范围或资源错配均安全拒绝。 */
    @Test
    @DisplayName("宿主描述各字段错配按组件或资源原因失败关闭")
    void hostDescriptorMismatchesFailClosed() {
        List<Optional<DashboardHostQualificationDescriptor>> componentFailures = List.of(
                Optional.empty(),
                Optional.of(hostDescriptor("tc.webapp-host/v2", "1.0.0",
                        Set.of("tc.dashboard/v1"), Map.of(componentKind(), "1.0.0"),
                        Map.of("empty_state", RESOURCE_DIGEST))),
                Optional.of(hostDescriptor("tc.webapp-host/v1", "1.0.0",
                        Set.of("tc.dashboard/v2"), Map.of(componentKind(), "1.0.0"),
                        Map.of("empty_state", RESOURCE_DIGEST))),
                Optional.of(hostDescriptor("tc.webapp-host/v1", "invalid",
                        Set.of("tc.dashboard/v1"), Map.of(componentKind(), "1.0.0"),
                        Map.of("empty_state", RESOURCE_DIGEST))),
                Optional.of(hostDescriptor("tc.webapp-host/v1", "1.0.1",
                        Set.of("tc.dashboard/v1"), Map.of(componentKind(), "1.0.0"),
                        Map.of("empty_state", RESOURCE_DIGEST))),
                Optional.of(hostDescriptor("tc.webapp-host/v1", "1.0.0",
                        Set.of("tc.dashboard/v1"), Map.of(),
                        Map.of("empty_state", RESOURCE_DIGEST))),
                Optional.of(hostDescriptor("tc.webapp-host/v1", "1.0.0",
                        Set.of("tc.dashboard/v1"), Map.of(componentKind(), "1.0.1"),
                        Map.of("empty_state", RESOURCE_DIGEST))));
        DashboardPublicationCandidate componentCandidate = candidate(List.of(hostRequirement()));
        for (Optional<DashboardHostQualificationDescriptor> descriptor : componentFailures) {
            QualificationFixture fixture = fixture();
            when(fixture.host.current()).thenReturn(descriptor);
            assertSafeFailure(fixture.service, componentCandidate,
                    DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
            verifyNoInteractions(fixture.models, fixture.properties, fixture.devices, fixture.adapters);
        }

        QualificationFixture resourceFixture = fixture();
        when(resourceFixture.host.current()).thenReturn(Optional.of(hostDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.dashboard/v1"),
                Map.of(componentKind(), "1.0.0"), Map.of("empty_state", "d".repeat(64)))));
        assertSafeFailure(resourceFixture.service, candidate(List.of(resourceRequirement())),
                DashboardPublicationQualificationException.Reason.BUILTIN_RESOURCE_UNAVAILABLE);

        QualificationFixture oversizedSemVerFixture = fixture();
        when(oversizedSemVerFixture.host.current()).thenReturn(Optional.of(hostDescriptor(
                "tc.webapp-host/v1", "1.70000.0", Set.of("tc.dashboard/v1"),
                Map.of(componentKind(), "1.0.0"), Map.of())));
        DashboardPublicationEligibilityRequirement.HostComponent broadRange =
                new DashboardPublicationEligibilityRequirement.HostComponent(
                        componentKind(), "1.0.0", "1.0.0", "2.0.0");
        assertSafeFailure(oversizedSemVerFixture.service, candidate(List.of(broadRange)),
                DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
    }

    /** 合法空画布没有组件需求也不能绕过生产宿主描述缺失。 */
    @Test
    @DisplayName("空画布在宿主描述缺失时仍失败关闭")
    void emptyCanvasStillFailsClosedWhenHostDescriptorIsMissing() {
        QualificationFixture fixture = fixture();

        assertSafeFailure(fixture.service, candidate(List.of()),
                DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
        verify(fixture.host).current();
        verifyNoInteractions(fixture.models, fixture.properties, fixture.devices, fixture.adapters);
    }

    /** 空画布仍须核验宿主版本，SemVer段超过65535时不能因没有组件需求而绕过。 */
    @Test
    @DisplayName("空画布仍拒绝超界宿主版本")
    void emptyCanvasStillRejectsOutOfRangeHostVersionSegments() {
        QualificationFixture fixture = fixture();
        when(fixture.host.current()).thenReturn(Optional.of(hostDescriptor(
                "tc.webapp-host/v1", "1.70000.0", Set.of("tc.dashboard/v1"),
                Map.of(componentKind(), "1.0.0"), Map.of())));

        assertSafeFailure(fixture.service, candidate(List.of()),
                DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
        verify(fixture.host).current();
        verifyNoInteractions(fixture.models, fixture.properties, fixture.devices, fixture.adapters);
    }

    /** 历史粒度与聚合组合缺少精确适配时不能由通用数据能力替代。 */
    @Test
    @DisplayName("历史精确适配缺失时失败关闭")
    void missingHistoricalAdapterCombinationFailsClosed() {
        QualificationFixture fixture = qualifiedFixture();
        when(fixture.adapters.supportsHistory(PROJECT_ID, historicalRequirement())).thenReturn(false);

        assertSafeFailure(fixture.service, candidate(List.of(
                        modelRequirement(), historicalRequirement())),
                DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE);
    }

    /** 首个失败必须停止后续端口且错误文本不包含候选或权威事实。 */
    @Test
    @DisplayName("首个资格失败稳定短路且不泄露外部事实")
    void firstFailureShortCircuitsWithoutLeakingFacts() {
        QualificationFixture fixture = qualifiedFixture();
        when(fixture.host.current()).thenReturn(Optional.of(hostDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.dashboard/v1"),
                Map.of(componentKind(), "1.0.1"), Map.of("empty_state", RESOURCE_DIGEST))));
        DashboardPublicationCandidate candidate = candidate(allRequirements());

        assertThatThrownBy(() -> fixture.service.qualify(candidate))
                .isInstanceOfSatisfying(DashboardPublicationQualificationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(
                            DashboardPublicationQualificationException.Reason.HOST_COMPONENT_UNAVAILABLE);
                    assertThat(failure.getMessage()).isEqualTo("看板宿主组件不可用")
                            .doesNotContain(PROJECT_ID.toString(), MODEL_VERSION_ID.toString(), MODEL_DIGEST);
                });
        verifyNoInteractions(fixture.properties, fixture.devices, fixture.adapters);
    }

    /** 任一权威端口异常必须原样传播，不能转为安全缺失或降级成功。 */
    @Test
    @DisplayName("权威端口异常原样传播且不降级")
    void authorityPortFailuresPropagateWithoutFallback() {
        RuntimeException authorityFailure = new RuntimeException("authority unavailable");

        QualificationFixture modelFixture = fixture();
        when(modelFixture.host.current()).thenReturn(Optional.of(hostDescriptor()));
        when(modelFixture.models.find(PROJECT_ID, MODEL_VERSION_ID)).thenThrow(authorityFailure);
        assertThatThrownBy(() -> modelFixture.service.qualify(candidate(List.of(modelRequirement()))))
                .isSameAs(authorityFailure);

        QualificationFixture hostFixture = qualifiedFixture();
        when(hostFixture.host.current()).thenThrow(authorityFailure);
        assertThatThrownBy(() -> hostFixture.service.qualify(candidate(List.of(hostRequirement()))))
                .isSameAs(authorityFailure);

        QualificationFixture deviceFixture = qualifiedFixture();
        when(deviceFixture.devices.find(PROJECT_ID, DEVICE_ID)).thenThrow(authorityFailure);
        assertThatThrownBy(() -> deviceFixture.service.qualify(candidate(List.of(
                modelRequirement(), defaultDeviceRequirement())))).isSameAs(authorityFailure);

        QualificationFixture propertyFixture = qualifiedFixture();
        when(propertyFixture.properties.find(PROJECT_ID, MODEL_VERSION_ID, "temperature"))
                .thenThrow(authorityFailure);
        assertThatThrownBy(() -> propertyFixture.service.qualify(candidate(List.of(
                modelRequirement(), propertyRequirement())))).isSameAs(authorityFailure);

        QualificationFixture adapterFixture = qualifiedFixture();
        when(adapterFixture.adapters.supports(eq(PROJECT_ID), any())).thenThrow(authorityFailure);
        assertThatThrownBy(() -> adapterFixture.service.qualify(candidate(List.of(
                modelRequirement(), adapterRequirement(
                        DashboardPublicationEligibilityRequirement.AdapterCapability.CURRENT_VALUE)))))
                .isSameAs(authorityFailure);
    }

    /** 资格值无公开构造器且底层候选仍保持JSON与集合防御边界。 */
    @Test
    @DisplayName("未资格候选无法公开伪装且资格值保持防御复制")
    void unqualifiedCandidateCannotBePubliclyForgedAndQualifiedValueRemainsDefensive()
            throws NoSuchMethodException {
        QualificationFixture fixture = qualifiedFixture();
        DashboardPublicationCandidate candidate = candidate(allRequirements());
        QualifiedDashboardPublicationCandidate qualified = fixture.service.qualify(candidate);

        assertThat(Modifier.isFinal(QualifiedDashboardPublicationCandidate.class.getModifiers())).isTrue();
        assertThat(QualifiedDashboardPublicationCandidate.class.getConstructors()).isEmpty();
        assertThat(Modifier.isPublic(DashboardPublicationQualificationService.class
                .getDeclaredMethod("qualify", DashboardPublicationCandidate.class).getModifiers())).isFalse();
        ObjectNode escaped = (ObjectNode) qualified.candidate().normalizedSchema();
        escaped.put("schemaVersion", "tampered");

        assertThat(qualified.candidate().normalizedSchema().path("schemaVersion").asString())
                .isEqualTo("tc.dashboard/v1");
        assertThatThrownBy(() -> qualified.candidate().eligibilityRequirements().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 创建已配置全部成功权威事实的测试夹具。 */
    private static QualificationFixture qualifiedFixture() {
        QualificationFixture fixture = fixture();
        when(fixture.host.current()).thenReturn(Optional.of(hostDescriptor()));
        when(fixture.models.find(PROJECT_ID, MODEL_VERSION_ID))
                .thenReturn(Optional.of(modelDescriptor()));
        when(fixture.properties.find(PROJECT_ID, MODEL_VERSION_ID, "temperature"))
                .thenReturn(Optional.of(numberProperty()));
        when(fixture.devices.find(PROJECT_ID, DEVICE_ID))
                .thenReturn(Optional.of(new DeviceModelBindingFacts(
                        DEVICE_ID, PROJECT_ID, MODEL_VERSION_ID)));
        when(fixture.adapters.supports(eq(PROJECT_ID), any())).thenReturn(true);
        when(fixture.adapters.supportsHistory(eq(PROJECT_ID), any())).thenReturn(true);
        return fixture;
    }

    /** 创建四个独立权威端口和被测服务。 */
    private static QualificationFixture fixture() {
        ThingModelVersionDescriptorPort models = mock(ThingModelVersionDescriptorPort.class);
        ThingModelPropertyFactsPort properties = mock(ThingModelPropertyFactsPort.class);
        DeviceModelBindingFactsPort devices = mock(DeviceModelBindingFactsPort.class);
        DashboardHostQualificationPort host = mock(DashboardHostQualificationPort.class);
        DashboardDataAdapterQualificationPort adapters = mock(DashboardDataAdapterQualificationPort.class);
        return new QualificationFixture(models, properties, devices, host, adapters,
                new DashboardPublicationQualificationService(models, properties, devices, host, adapters));
    }

    /** 断言稳定原因和固定安全消息，避免测试只看到异常类型。 */
    private static void assertSafeFailure(
            DashboardPublicationQualificationService service,
            DashboardPublicationCandidate candidate,
            DashboardPublicationQualificationException.Reason reason) {
        assertThatThrownBy(() -> service.qualify(candidate))
                .isInstanceOfSatisfying(DashboardPublicationQualificationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.getMessage()).isNotBlank()
                            .doesNotContain(PROJECT_ID.toString(), MODEL_VERSION_ID.toString(), MODEL_DIGEST);
                });
    }

    /** 构造恰含八类需求且依赖顺序可观察的完整候选需求。 */
    private static List<DashboardPublicationEligibilityRequirement> allRequirements() {
        return List.of(
                hostRequirement(), resourceRequirement(), modelRequirement(),
                defaultDeviceRequirement(), propertyRequirement(), historicalRequirement(),
                gaugeRequirement(), adapterRequirement(
                        DashboardPublicationEligibilityRequirement.AdapterCapability.CURRENT_VALUE));
    }

    /** 构造精确宿主组件需求。 */
    private static DashboardPublicationEligibilityRequirement.HostComponent hostRequirement() {
        return new DashboardPublicationEligibilityRequirement.HostComponent(
                DashboardPublicationEligibilityRequirement.ComponentKind.GAUGE,
                "1.0.0", "1.0.0", "1.0.1");
    }

    /** @return 当前宿主组件测试采用的GAUGE枚举身份 */
    private static DashboardPublicationEligibilityRequirement.ComponentKind componentKind() {
        return DashboardPublicationEligibilityRequirement.ComponentKind.GAUGE;
    }

    /** 构造可通过当前完整候选的宿主权威快照。 */
    private static DashboardHostQualificationDescriptor hostDescriptor() {
        return hostDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.dashboard/v1"),
                Map.of(componentKind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST));
    }

    /** 构造指定格式、版本、Schema、组件和资源事实的宿主快照。 */
    private static DashboardHostQualificationDescriptor hostDescriptor(
            String formatVersion, String hostVersion, Set<String> schemas,
            Map<DashboardPublicationEligibilityRequirement.ComponentKind, String> components,
            Map<String, String> resources) {
        return new DashboardHostQualificationDescriptor(
                formatVersion, hostVersion, Set.of("tc.application/v1"), schemas, components, resources);
    }

    /** 构造精确内置资源需求。 */
    private static DashboardPublicationEligibilityRequirement.BuiltinResource resourceRequirement() {
        return new DashboardPublicationEligibilityRequirement.BuiltinResource(
                "empty_state", RESOURCE_DIGEST);
    }

    /** 构造精确模型版本需求。 */
    private static DashboardPublicationEligibilityRequirement.ModelReference modelRequirement() {
        return new DashboardPublicationEligibilityRequirement.ModelReference(
                "pump_model", MODEL_VERSION_ID, "PG_JSONB_TEXT_V1_SHA256",
                MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 构造默认设备需求。 */
    private static DashboardPublicationEligibilityRequirement.DefaultDevice defaultDeviceRequirement() {
        return new DashboardPublicationEligibilityRequirement.DefaultDevice(
                "pump", DashboardPublicationEligibilityRequirement.VariableType.DEVICE_SINGLE,
                "pump_model", DEVICE_ID);
    }

    /** 构造允许NUMBER的顶层属性需求。 */
    private static DashboardPublicationEligibilityRequirement.ModelProperty propertyRequirement() {
        return new DashboardPublicationEligibilityRequirement.ModelProperty(
                "pump_model", "temperature",
                Set.of(DashboardPublicationEligibilityRequirement.PropertyDataType.NUMBER));
    }

    /** 构造NUMBER历史属性需求。 */
    private static DashboardPublicationEligibilityRequirement.HistoricalProperty historicalRequirement() {
        return new DashboardPublicationEligibilityRequirement.HistoricalProperty(
                "pump", "pump_model", "temperature", "period",
                DashboardPublicationEligibilityRequirement.HistoryGranularity.ONE_MINUTE,
                DashboardPublicationEligibilityRequirement.HistoryAggregation.AVG);
    }

    /** 构造MODEL量程需求。 */
    private static DashboardPublicationEligibilityRequirement.ModelGaugeRange gaugeRequirement() {
        return new DashboardPublicationEligibilityRequirement.ModelGaugeRange(
                "pump_model", "temperature");
    }

    /** 构造指定能力的数据适配需求。 */
    private static DashboardPublicationEligibilityRequirement.DataAdapter adapterRequirement(
            DashboardPublicationEligibilityRequirement.AdapterCapability capability) {
        return new DashboardPublicationEligibilityRequirement.DataAdapter(
                capability, DashboardPublicationEligibilityRequirement.BindingSource.CURRENT_VALUE,
                "pump", DashboardPublicationEligibilityRequirement.VariableType.DEVICE_SINGLE,
                "pump_model", "temperature");
    }

    /** 构造符合MODEL量程的NUMBER属性。 */
    private static ThingModelPropertyFacts numberProperty() {
        return numberProperty(new BigDecimal("-40"), new BigDecimal("125"));
    }

    /** 构造自定义边界的NUMBER属性。 */
    private static ThingModelPropertyFacts numberProperty(BigDecimal minimum, BigDecimal maximum) {
        return new ThingModelPropertyFacts(
                "temperature", ThingModelPropertyFacts.DataType.NUMBER, minimum, maximum);
    }

    /** 构造固定身份、摘要与Profile的模型描述。 */
    private static ThingModelVersionDescriptor modelDescriptor() {
        return modelDescriptor(PROJECT_ID, MODEL_VERSION_ID,
                "PG_JSONB_TEXT_V1_SHA256", MODEL_DIGEST, "TC_PROPERTY_COMPOSITE_V1");
    }

    /** 构造指定身份或摘要字段的模型描述。 */
    private static ThingModelVersionDescriptor modelDescriptor(
            UUID projectId, UUID versionId, String algorithm, String digest, String profile) {
        return new ThingModelVersionDescriptor(versionId, projectId, algorithm, digest, profile);
    }

    /** 构造公开值对象围栏认可的最小候选。 */
    private static DashboardPublicationCandidate candidate(
            List<DashboardPublicationEligibilityRequirement> requirements) {
        List<DashboardPublicationEligibilityRequirement.ModelReference> modelRequirements = requirements.stream()
                .filter(DashboardPublicationEligibilityRequirement.ModelReference.class::isInstance)
                .map(DashboardPublicationEligibilityRequirement.ModelReference.class::cast)
                .toList();
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        List<DashboardModelReference> modelReferences = new ArrayList<>();
        if (!modelRequirements.isEmpty()) {
            ArrayNode models = schema.putArray("models");
            for (int index = 0; index < modelRequirements.size(); index++) {
                DashboardPublicationEligibilityRequirement.ModelReference model = modelRequirements.get(index);
                models.addObject().put("key", model.modelKey()).put("versionId", model.versionId().toString());
                modelReferences.add(new DashboardModelReference(index, model.modelKey(), model.versionId()));
            }
        }
        List<DashboardRequiredComponent> components = requirements.stream()
                .filter(DashboardPublicationEligibilityRequirement.HostComponent.class::isInstance)
                .map(DashboardPublicationEligibilityRequirement.HostComponent.class::cast)
                .map(value -> new DashboardRequiredComponent(value.kind().name(), value.componentVersion()))
                .distinct()
                .sorted(Comparator.comparing(DashboardRequiredComponent::kind)
                        .thenComparing(DashboardRequiredComponent::componentVersion))
                .toList();
        List<DashboardRequiredResource> resources = requirements.stream()
                .filter(DashboardPublicationEligibilityRequirement.BuiltinResource.class::isInstance)
                .map(DashboardPublicationEligibilityRequirement.BuiltinResource.class::cast)
                .map(value -> new DashboardRequiredResource(value.resourceId(), value.digest()))
                .distinct()
                .sorted(Comparator.comparing(DashboardRequiredResource::resourceId))
                .toList();
        return new DashboardPublicationCandidate(
                UUID.randomUUID(), UUID.randomUUID(), PROJECT_ID, 7,
                schema, schema.toString(), "PG_JSONB_TEXT_V1_SHA256", SCHEMA_DIGEST,
                modelReferences, components, resources, requirements);
    }

    /** 被测服务及四个独立权威端口替身。 */
    private static final class QualificationFixture {
        /** 模型权威端口。 */
        private final ThingModelVersionDescriptorPort models;
        /** 模型属性权威端口。 */
        private final ThingModelPropertyFactsPort properties;
        /** 设备绑定权威端口。 */
        private final DeviceModelBindingFactsPort devices;
        /** 宿主注册权威端口。 */
        private final DashboardHostQualificationPort host;
        /** 数据适配权威端口。 */
        private final DashboardDataAdapterQualificationPort adapters;
        /** 被测资格编排服务。 */
        private final DashboardPublicationQualificationService service;

        /** 创建全部依赖独立的测试夹具。 */
        private QualificationFixture(
                ThingModelVersionDescriptorPort models,
                ThingModelPropertyFactsPort properties,
                DeviceModelBindingFactsPort devices,
                DashboardHostQualificationPort host,
                DashboardDataAdapterQualificationPort adapters,
                DashboardPublicationQualificationService service) {
            this.models = models;
            this.properties = properties;
            this.devices = devices;
            this.host = host;
            this.adapters = adapters;
            this.service = service;
        }
    }
}
