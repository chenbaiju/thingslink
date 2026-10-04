package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 受控配置启动资格与精确范围隔离。 */
class OtaTypeBaselineSourceTests {
    /** 公开夹具租户。 */
    private static final UUID TENANT = new UUID(0, 1);
    /** 公开夹具项目。 */
    private static final UUID PROJECT = new UUID(0, 2);
    /** 公开夹具类型。 */
    private static final UUID TYPE = new UUID(0, 3);
    /** 基线启动快照不因另一实例升级而改变，且必须匹配全部范围。 */
    @Test void snapshotsRemainImmutableAndScopeExact() throws Exception {
        var first = new OtaTypeBaselineSource(config(List.of(fields())));
        var upgraded = fields(); upgraded.put("baselineVersion", 2L);
        var second = new OtaTypeBaselineSource(config(List.of(upgraded)));
        assertEquals(1, first.require(TENANT, PROJECT, TYPE).value().baselineVersion());
        assertEquals(2, second.require(TENANT, PROJECT, TYPE).value().baselineVersion());
        assertThrows(IllegalArgumentException.class, () -> first.require(PROJECT, PROJECT, TYPE));
        assertThrows(IllegalArgumentException.class, () -> first.require(TENANT, TENANT, TYPE));
        assertThrows(IllegalArgumentException.class, () -> first.require(TENANT, PROJECT, PROJECT));
        assertThrows(IllegalArgumentException.class, () -> new OtaTypeBaselineSource("").require(TENANT, PROJECT, TYPE));
    }
    /** 重复身份或任一坏条目整体拒绝，不保留部分配置成功。 */
    @Test void rejectsDuplicateAndPartialInvalidConfiguration() throws Exception {
        String duplicate = config(List.of(fields(), fields()));
        assertThrows(IllegalArgumentException.class, () -> new OtaTypeBaselineSource(duplicate));
        var invalid = fields(); invalid.put("propertyProfile", "scalar-only");
        String mixed = config(List.of(fields(), invalid));
        assertThrows(IllegalArgumentException.class, () -> new OtaTypeBaselineSource(mixed));
        var distinct = fields(); distinct.put("deviceTypeId", new UUID(0, 4).toString());
        var source = new OtaTypeBaselineSource(config(List.of(fields(), distinct)));
        assertEquals(1, source.require(TENANT, PROJECT, new UUID(0, 4)).value().baselineVersion());
    }
    /** 外层闭集、编码和总预算同样在启动期拒绝。 */
    @Test void rejectsMalformedOuterConfiguration() {
        for (String config : List.of(" ", "{}", "{\"baselines\":null}", "{\"baselines\":[],\"x\":1}",
                "{\"baselines\":[],\"baselines\":[]}", "\ud800", " ".repeat(65_537))) {
            assertThrows(IllegalArgumentException.class, () -> new OtaTypeBaselineSource(config));
        }
    }
    /** 以真实规范器构造测试启动配置。 */
    private static String config(List<Map<String, Object>> entries) {
        return new String(new OtaCanonicalJson().writeObject(Map.of("baselines", entries)), StandardCharsets.UTF_8);
    }
    /** 独立公开向量的可变副本。 */
    private static Map<String, Object> fields() throws Exception {
        return new LinkedHashMap<>(new OtaCanonicalJson().parseObject(OtaTypeBaselineCodecTests.resource("type-baseline-v1.json")));
    }
}
