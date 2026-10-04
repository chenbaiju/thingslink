package com.things.link.telemetry.api.controller;

import com.things.link.project.application.ProjectService;
import com.things.link.telemetry.application.ConsoleVersionedHistoryService;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryPoint;
import com.things.link.telemetry.domain.PropertyHistoryResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 检查最终真实UTF-8编码预算，不以点数上限推断响应字节。 */
class ConsoleVersionedHistoryControllerTests {
    /** HTTP首层项目授权。 */
    private final ProjectService projects = mock(ProjectService.class);
    /** 领域响应来源。 */
    private final ConsoleVersionedHistoryService service = mock(ConsoleVersionedHistoryService.class);
    /** 保留实际Java时间序列化模块。 */
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    /** 实际生产控制器。 */
    private final ConsoleVersionedHistoryController controller = new ConsoleVersionedHistoryController(projects, service, mapper);

    /** 合法响应完整保留版本字段与no-store；JSON数值来自既有double域。 */
    @Test void serializesCompleteVersionedResponseWithoutCaching() {
        stub("1.0.0", 1);
        var response = query();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        var json = mapper.readTree(response.getBody());
        assertThat(json.path("points")).hasSize(1);
        assertThat(json.path("points").get(0).path("value").asDouble()).isEqualTo(12.5);
        assertThat(json.path("points").get(0).path("modelVersion").asString()).isEqualTo("1.0.0");
    }

    /** 即使点数只有2000，损坏来源标签形成超4MiB正文仍整体报错而非截断。 */
    @Test void rejectsOversizedActualEncodingWithoutTruncating() {
        stub("x".repeat(3000), 2000);
        assertThatThrownBy(this::query).isInstanceOf(IllegalStateException.class).hasMessageContaining("4MiB");
    }

    /** @param version 来源标签 @param count 点数 */
    private void stub(String version, int count) {
        var point = new PropertyHistoryPoint(Instant.parse("2026-09-06T11:30:00Z"), 12.5, 1, UUID.randomUUID(), version);
        when(service.query(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PropertyHistoryResult(HistoryGranularity.RAW, HistoryGranularity.RAW,
                        HistoryAggregation.AVG, Collections.nCopies(count, point)));
    }

    /** @return 固定合法参数的真实编码响应 */
    private org.springframework.http.ResponseEntity<byte[]> query() {
        return controller.query(UUID.randomUUID(), UUID.randomUUID(), "temperature", UUID.randomUUID(),
                Instant.parse("2026-09-06T11:00:00Z"), Instant.parse("2026-09-06T12:00:00Z"),
                HistoryGranularity.RAW, HistoryAggregation.AVG, new MockHttpServletRequest());
    }
}
