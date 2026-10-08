package com.things.link.telemetry.application;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class EventHistoryCursorTests {
    private final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), message = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-10-06T00:00:00.123456Z");
    @Test void bindsRawFiltersAndBothResourceAxesButNotPageSize() {
        var query = EventHistoryQuery.parse(Map.of("level", new String[]{"INFO"}, "limit", new String[]{"1"}));
        String cursor = EventHistoryCursor.encode(project, device, query, time, message);
        var changedLimit = EventHistoryQuery.parse(Map.of("level", new String[]{"INFO"}, "limit", new String[]{"100"}));
        assertThat(EventHistoryCursor.decode(cursor, project, device, changedLimit)).isEqualTo(new EventHistoryCursor.Anchor(time, message));
        assertThatThrownBy(() -> EventHistoryCursor.decode(cursor, UUID.randomUUID(), device, query)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EventHistoryCursor.decode(cursor, project, UUID.randomUUID(), query)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EventHistoryCursor.decode(cursor, project, device, EventHistoryQuery.parse(Map.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EventHistoryCursor.decode(cursor, project, device,
                EventHistoryQuery.parse(Map.of("level", new String[]{"INFO"}, "from", new String[]{"2026-10-01T00:00:00Z"})))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsMalformedClosedStructureDuplicateKeysTrailingJsonAndNanos() {
        var query = EventHistoryQuery.parse(Map.of());
        String valid = EventHistoryCursor.encode(project, device, query, time, message);
        String raw = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);
        for (String invalid : java.util.List.of("", valid + "=", "a".repeat(8193), encode(raw + "{}"),
                encode(raw.replace("\"v\":1", "\"v\":1,\"v\":1")), encode(raw.replace("\"v\":1", "\"v\":2")),
                encode(raw.replace("\"v\":1", "\"v\":1,\"extra\":0")),
                encode(raw.replace(time.toString(), "2026-10-06T00:00:00.123456789Z")),
                encode(raw.replace(time.toString(), "+100000-01-01T00:00:00Z")))) {
            assertThatThrownBy(() -> EventHistoryCursor.decode(invalid, project, device, query))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode().code()).isEqualTo(10001));
        }
    }
    private static String encode(String raw) { return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)); }
}
