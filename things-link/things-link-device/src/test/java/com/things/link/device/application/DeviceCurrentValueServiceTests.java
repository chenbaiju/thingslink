package com.things.link.device.application;

import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceCurrentValueCache.ValueKey;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.device.domain.DeviceShadowSnapshot;
import com.things.link.device.domain.DeviceReportedRevisionSnapshot;
import com.things.link.device.domain.DeviceCurrentValueCache.ReportedValue;
import com.things.link.project.application.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 批量当前值服务测试，固定热命中、回源和 Redis 故障降级语义。 */
@ExtendWith(MockitoExtension.class)
class DeviceCurrentValueServiceTests {
    /** 项目授权替身。 */
    @Mock private ProjectService projectService;
    /** PostgreSQL 事实仓储替身。 */
    @Mock private DeviceShadowRepository shadowRepository;
    /** Redis 热副本替身。 */
    @Mock private DeviceCurrentValueCache cache;
    /** 被测服务。 */
    private DeviceCurrentValueService service;
    /** 项目 ID。 */
    private UUID projectId;
    /** 设备 ID。 */
    private UUID deviceId;
    /** 统一 JSON 解析器。 */
    private ObjectMapper objectMapper;

    /** 初始化无数据库设备预查的服务实例。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new DeviceCurrentValueService(projectService, shadowRepository, cache, objectMapper);
        projectId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
    }

    /** 全量热命中仅核验 PostgreSQL 序号，不读取属性全文。 */
    @Test
    void returnsCacheHitWithoutDatabaseQuery() {
        ValueKey key = new ValueKey(deviceId, "temperature");
        DeviceCurrentValue cached = new DeviceCurrentValue(deviceId, "temperature",
                objectMapper.readTree("26.5"), Instant.parse("2026-08-08T01:00:00Z"), 2, "9", null);
        when(cache.findAll(projectId, List.of(key))).thenReturn(Map.of(key, cached));
        when(shadowRepository.findReportedRevisions(projectId, List.of(deviceId)))
                .thenReturn(List.of(new DeviceReportedRevisionSnapshot(deviceId, "{\"temperature\":\"9\"}")));

        assertThat(service.findAll(projectId, List.of(deviceId), List.of("temperature")))
                .containsExactly(cached);
        verify(shadowRepository, never()).findSnapshots(org.mockito.ArgumentMatchers.any(), anyCollection());
    }

    /** 缓存缺失时一次回源 PostgreSQL，并用事实采集时间回填。 */
    @Test
    void fallsBackAndBackfillsOnCacheMiss() {
        when(cache.findAll(org.mockito.ArgumentMatchers.eq(projectId), anyCollection())).thenReturn(Map.of());
        when(shadowRepository.findSnapshots(org.mockito.ArgumentMatchers.eq(projectId), anyCollection()))
                .thenReturn(List.of(new DeviceShadowSnapshot(deviceId, "{\"temperature\":27}",
                        "{\"temperature\":\"2026-08-08T02:00:00Z\"}", 3, "{\"temperature\":\"5\"}", "{}")));

        List<DeviceCurrentValue> result = service.findAll(
                projectId, List.of(deviceId), List.of("temperature", "missing"));

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.value().asInt()).isEqualTo(27);
            assertThat(value.occurredAt()).isEqualTo(Instant.parse("2026-08-08T02:00:00Z"));
        });
        verify(cache).merge(projectId, deviceId, Map.of("temperature",
                new ReportedValue("27", Instant.parse("2026-08-08T02:00:00Z"), 3, "5", null)));
    }

    /** Redis 读写同时故障也必须返回 PostgreSQL 事实值。 */
    @Test
    void returnsDatabaseValueWhenRedisFails() {
        when(cache.findAll(org.mockito.ArgumentMatchers.eq(projectId), anyCollection()))
                .thenThrow(new IllegalStateException("redis down"));
        when(shadowRepository.findSnapshots(org.mockito.ArgumentMatchers.eq(projectId), anyCollection()))
                .thenReturn(List.of(new DeviceShadowSnapshot(deviceId, "{\"online\":true}",
                        "{\"online\":\"2026-08-08T03:00:00Z\"}", 0, "{\"online\":\"6\"}", "{}")));
        doThrow(new IllegalStateException("redis down")).when(cache).merge(
                org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.eq(deviceId),
                org.mockito.ArgumentMatchers.anyMap());

        assertThat(service.findAll(projectId, List.of(deviceId), List.of("online")))
                .singleElement().satisfies(value -> assertThat(value.value().asBoolean()).isTrue());
    }

    /** PG文本冷读和回填必须保留小数及嵌套超Long整数，不能共同舍入。 */
    @Test
    void preservesPostgresJsonPrecisionWhenReadingAndBackfilling() {
        String exact = "{\"decimal\":0.12345678901234567890123456789,"
                + "\"nested\":[{\"decimal\":123456789.1234567890123456789,"
                + "\"integer\":9223372036854775808123456789}]}";
        Instant occurredAt = Instant.parse("2026-08-08T02:00:00Z");
        when(cache.findAll(org.mockito.ArgumentMatchers.eq(projectId), anyCollection())).thenReturn(Map.of());
        when(shadowRepository.findSnapshots(org.mockito.ArgumentMatchers.eq(projectId), anyCollection()))
                .thenReturn(List.of(new DeviceShadowSnapshot(deviceId, "{\"precision\":" + exact + "}",
                        "{\"precision\":\"2026-08-08T02:00:00Z\"}", 7, "{\"precision\":\"7\"}", "{}")));

        List<DeviceCurrentValue> result = service.findAll(projectId, List.of(deviceId), List.of("precision"));

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(new BigDecimal(value.value().get("decimal").asText()))
                    .isEqualByComparingTo("0.12345678901234567890123456789");
            assertThat(new BigDecimal(value.value().get("nested").get(0).get("decimal").asText()))
                    .isEqualByComparingTo("123456789.1234567890123456789");
            assertThat(value.value().get("nested").get(0).get("integer").asText())
                    .isEqualTo("9223372036854775808123456789");
            assertThat(value.occurredAt()).isEqualTo(occurredAt);
            assertThat(value.shadowVersion()).isEqualTo(7);
        });
        verify(cache).merge(projectId, deviceId, Map.of("precision", new ReportedValue(exact, occurredAt, 7, "7", null)));
    }


    /** Redis淘汰后旧回调复活也不能绕过PG较新接受序号。 */
    @Test
    void rejectsStaleCacheAgainstDatabaseRevision() {
        ValueKey key = new ValueKey(deviceId, "value");
        when(cache.findAll(projectId, List.of(key))).thenReturn(Map.of(key,
                new DeviceCurrentValue(deviceId, "value", objectMapper.readTree("1"), Instant.EPOCH, 0, "1", null)));
        when(shadowRepository.findReportedRevisions(projectId, List.of(deviceId)))
                .thenReturn(List.of(new DeviceReportedRevisionSnapshot(deviceId, "{\"value\":\"2\"}")));
        when(shadowRepository.findSnapshots(projectId, java.util.Set.of(deviceId)))
                .thenReturn(List.of(new DeviceShadowSnapshot(deviceId, "{\"value\":2}",
                        "{\"value\":\"1970-01-01T00:00:00Z\"}", 0, "{\"value\":\"2\"}", "{}")));
        assertThat(service.findAll(projectId, List.of(deviceId), List.of("value")))
                .singleElement().satisfies(value -> assertThat(value.value().asInt()).isEqualTo(2));
    }

    /** 权威序号查询失败不能把旧Redis视作可用当前值。 */
    @Test
    void propagatesAuthoritativeRevisionReadFailure() {
        when(shadowRepository.findReportedRevisions(projectId, List.of(deviceId)))
                .thenThrow(new IllegalStateException("pg unavailable"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findAll(projectId, List.of(deviceId), List.of("value")))
                .hasMessage("pg unavailable");
    }

    /** 没有序号的历史值保持未知，模型来源保留原属性记录且不回填缓存。 */
    @Test
    void preservesUnknownHistoricalRevisionAndActualSource() {
        UUID source = UUID.randomUUID();
        when(shadowRepository.findSnapshots(projectId, java.util.Set.of(deviceId)))
                .thenReturn(List.of(new DeviceShadowSnapshot(deviceId, "{\"value\":1}",
                        "{\"value\":\"1970-01-01T00:00:00Z\"}", 99, "{}",
                        "{\"value\":\"" + source + "\"}")));
        assertThat(service.findAll(projectId, List.of(deviceId), List.of("value"))).singleElement().satisfies(value -> {
            assertThat(value.reportedRevision()).isNull();
            assertThat(value.thingModelVersionId()).isEqualTo(source);
        });
        verify(cache, never()).merge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

}
