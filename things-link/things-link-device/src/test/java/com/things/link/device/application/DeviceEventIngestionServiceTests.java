package com.things.link.device.application;

import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 事件设备端口单测：真实冻结快照校验，身份/版本仓储为替身，不冒充MQTT或PG资格。 */
@ExtendWith(MockitoExtension.class)
class DeviceEventIngestionServiceTests {
    @Mock private DeviceRepository devices;
    @Mock private DeviceTypeRepository types;
    @Mock private ThingModelVersionBindingService binding;
    @Mock private TransactionLocalRlsScope rls;
    @Mock private DevicePropertyDefinitionRepository properties;
    @Mock private DeviceShadowRepository shadows;
    @Mock private DeviceCurrentValueCache cache;
    @Mock private ApplicationEventPublisher publisher;
    private DeviceIngestionService service;
    private final UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), device = UUID.randomUUID();
    private final UUID type = UUID.randomUUID(), version = UUID.randomUUID();
    private final Instant receivedAt = Instant.parse("2026-10-06T12:00:00Z");
    private static final String SNAPSHOT = """
            {"properties":{},"events":{
              "fault":{"level":"WARNING","parameters":{
                "number":{"dataType":"NUMBER","required":false},
                "text":{"dataType":"TEXT","required":false},
                "switch":{"dataType":"SWITCH","required":false},
                "enum":{"dataType":"ENUM","required":false,"enum":["one","two"]}}},
              "required":{"level":"ERROR","parameters":{"text":{"dataType":"TEXT","required":true}}},
              "empty":{"level":"INFO","parameters":{}}},"commands":{}}
            """;

    @BeforeEach void setUp() {
        service = new DeviceIngestionService(devices, types, properties, shadows, new ObjectMapper(), cache,
                mock(DeviceCommandDefinitionRepository.class), mock(ThingModelSchemaValidator.class),
                mock(ProjectService.class), publisher, binding, rls, mock(DevicePropertyWebhookSource.class));
        lenient().when(devices.findById(project, device)).thenReturn(Optional.of(device(tenant, project, type, null)));
        lenient().when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, project,
                DeviceType.DeviceKind.DIRECT, DeviceType.Status.PUBLISHED)));
        lenient().when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt))
                .thenReturn(context(SNAPSHOT, DeviceIngestionContext.Eligibility.CURRENT));
    }

    @ParameterizedTest
    @EnumSource(DeviceIngestionContext.Eligibility.class)
    void freezesVersionLevelAndEligibilityWithoutPropertySideEffects(DeviceIngestionContext.Eligibility eligibility) {
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt)).thenReturn(context(SNAPSHOT, eligibility));
        Map<String, Object> params = new LinkedHashMap<>(Map.of("number", new BigDecimal("1.00"),
                "text", " 原文 ", "switch", true, "enum", "two"));
        DeviceEventIngestionContext actual = report("fault", params);
        assertThat(actual).isEqualTo(new DeviceEventIngestionContext(tenant, type, version, "1.0.0",
                "a".repeat(64), "PG_JSONB_TEXT_V1_SHA256", "fault", "WARNING", eligibility));
        assertThat(params.get("text")).isEqualTo(" 原文 ");
        var order = inOrder(rls, devices, types, binding);
        order.verify(rls).establish(tenant, project);
        order.verify(devices).findById(project, device);
        order.verify(types).findById(project, type);
        order.verify(binding).resolveForIngestion(project, device, "1.0.0", receivedAt);
        verifyNoInteractions(properties, shadows, cache, publisher);
    }

    @Test void gatewayOwnEventAndEmptyParametersAreAcceptedEvenAfterDisconnect() {
        when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, project,
                DeviceType.DeviceKind.GATEWAY, DeviceType.Status.PUBLISHED)));
        assertThat(report("empty", Map.of()).level()).isEqualTo("INFO");
        assertThat(report("fault", Map.of()).eventKey()).isEqualTo("fault");
    }

    @Test void rejectsSubDeviceAndBoundGatewayIdentity() {
        when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, project,
                DeviceType.DeviceKind.SUB_DEVICE, DeviceType.Status.PUBLISHED)));
        invalid(() -> report("empty", Map.of()));
        when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, project,
                DeviceType.DeviceKind.DIRECT, DeviceType.Status.PUBLISHED)));
        when(devices.findById(project, device)).thenReturn(Optional.of(device(tenant, project, type, UUID.randomUUID())));
        invalid(() -> report("empty", Map.of()));
        verifyNoInteractions(binding);
    }

    @Test void draftTypeCannotSupplyPublishedEvent() {
        when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, project,
                DeviceType.DeviceKind.DIRECT, DeviceType.Status.DRAFT)));
        invalid(() -> report("empty", Map.of()));
        verifyNoInteractions(binding);
    }

    @Test void rejectsMismatchedDeviceTenantAndTypeProject() {
        when(devices.findById(project, device)).thenReturn(Optional.of(device(UUID.randomUUID(), project, type, null)));
        code(() -> report("empty", Map.of()), DeviceErrorCode.DEVICE_NOT_FOUND);
        when(devices.findById(project, device)).thenReturn(Optional.of(device(tenant, project, type, null)));
        when(types.findById(project, type)).thenReturn(Optional.of(type(tenant, UUID.randomUUID(),
                DeviceType.DeviceKind.DIRECT, DeviceType.Status.PUBLISHED)));
        code(() -> report("empty", Map.of()), DeviceErrorCode.DEVICE_TYPE_NOT_FOUND);
        verifyNoInteractions(binding);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"01.0.0", "1.0", "1.0.0 ", "65536.0.0", "1.65536.0", "1.0.65536", "100000.0.0", "-1.0.0"})
    void explicitVersionIsRequiredBeforeBinding(String declared) {
        code(() -> service.validateReportedEvent(tenant, project, device, declared, receivedAt, "empty", Map.of()),
                DeviceErrorCode.THING_MODEL_VERSION_REQUIRED);
        verifyNoInteractions(binding);
    }

    @Test void versionBoundaryUsesExactDeclaredValueAndTrustedReceivedAt() {
        when(binding.resolveForIngestion(project, device, "65535.65535.65535", receivedAt))
                .thenReturn(new DeviceIngestionContext(tenant, version, "65535.65535.65535", "a".repeat(64),
                        "PG_JSONB_TEXT_V1_SHA256", SNAPSHOT, DeviceIngestionContext.Eligibility.HISTORY_ONLY, Map.of(), false));
        assertThat(service.validateReportedEvent(tenant, project, device, "65535.65535.65535", receivedAt,
                "empty", Map.of()).modelVersion()).isEqualTo("65535.65535.65535");
        verify(binding).resolveForIngestion(project, device, "65535.65535.65535", receivedAt);
    }

    @ParameterizedTest
    @EnumSource(value = DeviceErrorCode.class, names = {"THING_MODEL_VERSION_NOT_FOUND", "THING_MODEL_VERSION_HISTORY_EXPIRED"})
    void retainsBindingRejectionCodes(DeviceErrorCode error) {
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt)).thenThrow(new BusinessException(error));
        code(() -> report("empty", Map.of()), error);
    }

    @Test void rejectsInferredOrDifferentVersionAndTenantFromBinding() {
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt))
                .thenReturn(context(SNAPSHOT, DeviceIngestionContext.Eligibility.CURRENT).withLegacyInferred());
        invalid(() -> report("empty", Map.of()));
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt)).thenReturn(new DeviceIngestionContext(
                UUID.randomUUID(), version, "1.0.0", "a".repeat(64), "PG_JSONB_TEXT_V1_SHA256", SNAPSHOT,
                DeviceIngestionContext.Eligibility.CURRENT, Map.of(), false));
        invalid(() -> report("empty", Map.of()));
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt)).thenReturn(new DeviceIngestionContext(
                tenant, version, "1.0.1", "a".repeat(64), "PG_JSONB_TEXT_V1_SHA256", SNAPSHOT,
                DeviceIngestionContext.Eligibility.CURRENT, Map.of(), false));
        invalid(() -> report("empty", Map.of()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"_old", "-old", "bad/key", "fault ", "missing"})
    void invalidOrUndefinedMqttKeyCannotProduceContext(String key) { invalid(() -> report(key, Map.of())); }

    @Test void requiresTrustedTimeAndRequiredParametersAndRejectsExplicitNull() {
        invalid(() -> service.validateReportedEvent(tenant, project, device, "1.0.0", null, "empty", Map.of()));
        invalid(() -> report("required", Map.of()));
        invalid(() -> report("empty", null));
        Map<String, Object> params = new LinkedHashMap<>(); params.put("text", null);
        invalid(() -> report("fault", params));
        assertThat(report("required", Map.of("text", "")).level()).isEqualTo("ERROR");
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidValuesWithoutLeakingSyntheticMaterial(String key, Object value) {
        assertThatThrownBy(() -> report("fault", Map.of(key, value))).isInstanceOfSatisfying(BusinessException.class,
                exception -> {
                    assertThat(exception.errorCode()).isEqualTo(DeviceErrorCode.EVENT_REPORT_INVALID);
                    assertThat(exception.getMessage()).doesNotContain("synthetic-secret");
                });
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(Arguments.of("number", "1"), Arguments.of("number", true),
                Arguments.of("number", Double.NaN), Arguments.of("number", Double.POSITIVE_INFINITY),
                Arguments.of("number", new BigDecimal("1e309")), Arguments.of("number", new BigDecimal("1e-309")),
                Arguments.of("number", new BigInteger("1".repeat(39))),
                Arguments.of("number", new BigDecimal("123456789012345678901234567890123456789")),
                Arguments.of("text", "x".repeat(4097)), Arguments.of("text", "synthetic-secret\0suffix"), Arguments.of("text", "\uD800"),
                Arguments.of("text", "\uDC00"), Arguments.of("text", 1),
                Arguments.of("switch", "true"), Arguments.of("switch", 1), Arguments.of("enum", " one"),
                Arguments.of("enum", "synthetic-secret"), Arguments.of("enum", 1),
                Arguments.of("unknown", "synthetic-secret"), Arguments.of("bad/key", "synthetic-secret"),
                Arguments.of("number", Map.of("value", 1)), Arguments.of("text", java.util.List.of("one")));
    }

    @Test void acceptsExactDecimalAndUnicodeBudgetsWithoutTrimming() {
        for (Object value : java.util.List.of(new BigDecimal("1e308"), new BigDecimal("-1e308"),
                new BigDecimal("1e-308"), new BigDecimal("1".repeat(38)), new BigInteger("1".repeat(38)), 0, 1L, 0.5d, 0.5f)) {
            assertThat(report("fault", Map.of("number", value)).eventKey()).isEqualTo("fault");
        }
        assertThat(report("fault", Map.of("text", "😀".repeat(4096))).level()).isEqualTo("WARNING");
    }

    @Test void enumUsesExactFrozenTextAndSixtyFourCodePoints() {
        String option = "😀".repeat(64);
        String snapshot = SNAPSHOT.replace("[\"one\",\"two\"]", "[\"" + option + "\",\" \" ]");
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt))
                .thenReturn(context(snapshot, DeviceIngestionContext.Eligibility.CURRENT));
        assertThat(report("fault", Map.of("enum", option)).eventKey()).isEqualTo("fault");
        assertThat(report("fault", Map.of("enum", " ")).eventKey()).isEqualTo("fault");
        invalid(() -> report("fault", Map.of("enum", "")));
    }

    @Test void excessParameterBudgetAndNullKeyAreRejectedBeforeContextIsReturned() {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) params.put("p" + index, "value");
        invalid(() -> report("fault", params));
        params.clear(); params.put(null, "value");
        invalid(() -> report("fault", params));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{\"events\":{}}", "{\"events\":{\"fault\":{\"level\":\"ERROR\",\"parameters\":{\"p\":{\"dataType\":\"OBJECT\",\"required\":false}}}}}"})
    void rejectsMalformedOrUnsupportedFrozenSnapshot(String snapshot) {
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt))
                .thenReturn(context(snapshot, DeviceIngestionContext.Eligibility.CURRENT));
        invalid(() -> report("fault", Map.of()));
    }

    @Test void databaseFailuresStayRetryableRatherThanBecomingInvalidPayload() {
        var failure = new DataAccessResourceFailureException("synthetic database unavailable");
        when(binding.resolveForIngestion(project, device, "1.0.0", receivedAt)).thenThrow(failure);
        assertThatThrownBy(() -> report("empty", Map.of())).isSameAs(failure);
        when(types.findById(project, type)).thenThrow(failure);
        assertThatThrownBy(() -> report("empty", Map.of())).isSameAs(failure);
        verifyNoInteractions(properties, shadows, cache, publisher);
    }

    private DeviceEventIngestionContext report(String key, Map<String, Object> params) {
        return service.validateReportedEvent(tenant, project, device, "1.0.0", receivedAt, key, params);
    }
    private DeviceIngestionContext context(String snapshot, DeviceIngestionContext.Eligibility eligibility) {
        return new DeviceIngestionContext(tenant, version, "1.0.0", "a".repeat(64), "PG_JSONB_TEXT_V1_SHA256",
                snapshot, eligibility, Map.of(), false);
    }
    private Device device(UUID tenantId, UUID projectId, UUID typeId, UUID gateway) {
        return new Device(device, tenantId, projectId, typeId, gateway, "sensor", "传感器", null,
                Device.Status.OFFLINE, null, receivedAt.minusSeconds(1), receivedAt.minusSeconds(60));
    }
    private DeviceType type(UUID tenantId, UUID projectId, DeviceType.DeviceKind kind, DeviceType.Status status) {
        return new DeviceType(type, tenantId, projectId, "sensor", "传感器", kind,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1, status, null, null, receivedAt);
    }
    private static void invalid(Runnable action) { code(action, DeviceErrorCode.EVENT_REPORT_INVALID); }
    private static void code(Runnable action, DeviceErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(error));
    }
}
