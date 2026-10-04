package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 精确八字段通知、规范字节与Topic边界。 */
class OtaNotificationCodecTests {
    /** 独立手写黄金字节验证字段数量、整数与时刻，不借生产编码器生成预期值。 */
    @Test void fixedCanonicalNotification() {
        UUID id = new UUID(0, 1);
        var value = new OtaNotificationCodec.Notification("tc-ota-available/v1", id, id, id, id, 1,
                "a".repeat(64), Instant.parse("2026-09-12T00:00:00.123456Z"));
        String golden = "{\"attemptNo\":1,\"campaignId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"contractVersion\":\"tc-ota-available/v1\",\"deadlineAt\":\"2026-09-12T00:00:00.123456Z\","
                + "\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"firmwareId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"manifestSha256\":\"" + "a".repeat(64) + "\"}";
        var codec = new OtaNotificationCodec();
        assertArrayEquals(golden.getBytes(StandardCharsets.UTF_8), codec.encode(value));
        assertEquals(value, codec.decode(golden.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, codec.decode(golden.replace("\"attemptNo\":1", "\"attemptNo\":2").getBytes(StandardCharsets.UTF_8)).attemptNo());
        for (String invalid : new String[] {"0", "-1", "1.5", "2147483648", "\"2\""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(golden.replace("\"attemptNo\":1", "\"attemptNo\":" + invalid).getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(golden.replace("{", "{\"url\":\"secret\",").getBytes(StandardCharsets.UTF_8)));
    }
    /** 不能将路由段变成其他设备、通配符或控制字符Topic。 */
    @Test void strictRouteSegments() {
        assertEquals("tc/v1/project/device/down/ota/available", OtaNotificationCodec.topic("project", "device"));
        for (String invalid : new String[] {null, "", "a/b", "+", "#", "a\nb", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> OtaNotificationCodec.topic(invalid, "device"));
            assertThrows(IllegalArgumentException.class, () -> OtaNotificationCodec.topic("project", invalid));
        }
    }
}
