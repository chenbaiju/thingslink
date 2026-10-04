package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.device.infrastructure.schema.JacksonThingModelSchemaValidator;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 版本发布线路测试：完整快照、严格复合 Profile 和保守变化级别必须同时成立。 */
@ExtendWith(MockitoExtension.class)
class ThingModelVersionServiceTests {
    /** 持久化替身。 */ @Mock private ThingModelVersionRepository repository;
    /** 类型替身。 */ @Mock private DeviceTypeRepository typeRepository;
    /** 被测服务。 */ private ThingModelVersionService service;
    /** JSON。 */ private ObjectMapper objectMapper;
    /** 固定归属。 */ private UUID tenantId, projectId, typeId;

    /** 建立真实 Profile 校验器，避免发布测试把校验行为 mock 掉。 */
    @BeforeEach void setUp() {
        objectMapper = new ObjectMapper();
        service = new ThingModelVersionService(repository, typeRepository,
                new JacksonThingModelSchemaValidator(objectMapper), objectMapper);
        tenantId = UUID.randomUUID(); projectId = UUID.randomUUID(); typeId = UUID.randomUUID();
        when(typeRepository.findByIdForUpdate(projectId, typeId)).thenReturn(Optional.of(new DeviceType(
                typeId, tenantId, projectId, "meter", "电表", DeviceType.DeviceKind.DIRECT,
                DeviceType.PayloadProtocol.STANDARD, DeviceType.NetworkType.WIFI, 1,
                DeviceType.Status.PUBLISHED, null, null, Instant.now())));
    }

    /** 初始版本只能沿 1.0.0/MAJOR 发布。 */
    @Test void publishesInitialMajorVersion() {
        JsonNode snapshot = snapshot("""
                {"temperature":{"dataType":"NUMBER"}}
                """);
        when(repository.findLatest(projectId, typeId)).thenReturn(Optional.empty());
        when(repository.create(any(), eq(tenantId), eq(projectId), eq(typeId), eq("1.0.0"),
                eq(ThingModelVersion.ChangeLevel.MAJOR), any(), any())).thenReturn(version("1.0.0", snapshot));
        service.publish(projectId, typeId, "1.0.0", ThingModelVersion.ChangeLevel.MAJOR, snapshot);
    }

    /** 修改既有属性不能伪装成 PATCH。 */
    @Test void rejectsBreakingChangeDeclaredAsPatch() {
        JsonNode oldSnapshot = snapshot("{\"temperature\":{\"dataType\":\"NUMBER\"}}");
        JsonNode changed = snapshot("{\"temperature\":{\"dataType\":\"TEXT\"}}");
        when(repository.findLatest(projectId, typeId)).thenReturn(Optional.of(version("1.0.0", oldSnapshot)));
        assertThatThrownBy(() -> service.publish(projectId, typeId, "1.0.1",
                ThingModelVersion.ChangeLevel.PATCH, changed))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
    }

    /** 复合属性必须使用严格 Profile，未知 pattern 关键字不能进入不可变版本。 */
    @Test void rejectsCompositeSchemaOutsideProfile() {
        JsonNode snapshot = snapshot("""
                {"labels":{"dataType":"LIST","schema":{"type":"array","maxItems":4,
                  "items":{"type":"string","pattern":".*"}}}}
                """);
        assertThatThrownBy(() -> service.publish(projectId, typeId, "1.0.0",
                ThingModelVersion.ChangeLevel.MAJOR, snapshot))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
    }

    /** 组装固定三段快照。 */
    private JsonNode snapshot(String properties) {
        return objectMapper.readTree("{\"properties\":" + properties + ",\"events\":{},\"commands\":{}}");
    }

    /** 构造仓储中的旧版本。 */
    private ThingModelVersion version(String number, JsonNode snapshot) {
        return new ThingModelVersion(UUID.randomUUID(), tenantId, projectId, typeId, number,
                ThingModelVersion.ChangeLevel.MAJOR, snapshot.toString(), "a".repeat(64),
                "PG_JSONB_TEXT_V1_SHA256", Instant.now());
    }
}
