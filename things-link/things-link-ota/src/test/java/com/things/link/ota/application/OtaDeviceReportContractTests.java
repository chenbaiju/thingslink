package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 独立公开黄金报告及能力交集反例，不替代真实设备认证和数据库锁测试。 */
class OtaDeviceReportContractTests {
    /** 严格规范JSON。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 报告codec。 */
    private final OtaDeviceReportCodec codec = new OtaDeviceReportCodec();
    /** 纯兼容裁决。 */
    private final OtaDeviceEligibilityEvaluator evaluator = new OtaDeviceEligibilityEvaluator();
    /** 独立Python固定摘要、排序与防御复制。 */
    @Test void goldenCanonicalAndImmutableLists() throws Exception {
        byte[] bytes = resource("device-report-v1.json");
        var decoded = codec.decode(bytes);
        assertArrayEquals(bytes,decoded.canonical());
        assertEquals(new String(resource("device-report-v1.sha256"),StandardCharsets.US_ASCII).trim(),decoded.sha256());
        var reversed = report(); reversed.put("signatureProfiles",List.of("TC_OTA_ES256_P1363_V1","TC_OTA_ED25519_V1"));
        assertEquals(decoded.sha256(),codec.decode(json.writeObject(reversed)).sha256());
        byte[] copy = decoded.canonical(); copy[0]=0; assertArrayEquals(bytes,decoded.canonical());
        assertThrows(UnsupportedOperationException.class,()->decoded.value().signatureProfiles().clear());
    }
    /** 类型、槽、范围、计数器及认证身份注入均严格拒绝。 */
    @Test void rejectsMalformedAndInconsistentReports() throws Exception {
        for (Map.Entry<String,Object> bad : Map.<String,Object>ofEntries(
                Map.entry("tenantId","00000000-0000-0000-0000-000000000001"),Map.entry("reportSequence",0L),
                Map.entry("currentSecurityVersion",0L),Map.entry("activeSlot","SINGLE"),Map.entry("bootState","OK"),
                Map.entry("bootloaderVersion","01.0.0"),Map.entry("protectedSecurityCounterBits",54L),
                Map.entry("supportsRangeDownload",false),Map.entry("supportsAbSlots","true"),
                Map.entry("maximumArtifactBytes",67_108_865L),Map.entry("signatureProfiles",List.of()),
                Map.entry("deltaModes",List.of("PATCH"))).entrySet()) {
            var data=report();data.put(bad.getKey(),bad.getValue());
            assertThrows(IllegalArgumentException.class,()->codec.decode(json.writeObject(data)),bad.getKey());
        }
        assertThrows(IllegalArgumentException.class,()->codec.decode(new byte[65_537]));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new byte[]{(byte)0xc3,0x28}));
    }
    /** 完整交集可通过，依赖缺失不伪装成兼容结果。 */
    @Test void acceptsFullIntersectionOnly() throws Exception {
        assertEquals(OtaDeviceEligibilityEvaluator.Reason.ELIGIBLE,evaluate(report(),manifest()));
        var baseline=new OtaTypeBaselineCodec().decode(resource("baseline/type-baseline-v1.json"));
        assertEquals(OtaDeviceEligibilityEvaluator.Reason.REPORT_MISSING,evaluator.evaluate(null,baseline,
                json.writeObject(manifest()),1,"d".repeat(64),"a".repeat(64),"test.domain"));
    }
    /** 运行未提交、资源越界、硬件不符与实际能力不足均拒绝。 */
    @Test void refusesNarrowedOrInflatedCapabilities() throws Exception {
        for (Map.Entry<String,Object> bad : Map.<String,Object>ofEntries(
                Map.entry("currentSecurityVersion",2L),Map.entry("bootState","TESTING"),
                Map.entry("availableRamBytes",65_535L),Map.entry("availableFlashBytes",1_048_577L),
                Map.entry("protectedSecurityCounterBits",0L),Map.entry("bootloaderVersion","1.11.0"),
                Map.entry("hardware",Map.of("model","wrong","boardRevision",1L)),
                Map.entry("maximumArtifactBytes",512L),Map.entry("signatureProfiles",List.of("TC_OTA_ES256_P1363_V1")))
                .entrySet()) {
            var data=report();data.put(bad.getKey(),bad.getValue());
            assertEquals(OtaDeviceEligibilityEvaluator.Reason.INCOMPATIBLE,evaluate(data,manifest()),bad.getKey());
        }
    }
    /** 包必须精确匹配，声称更大版本不等价于确认当前包。 */
    @Test void requiresExactCurrentTrustAcknowledgement() throws Exception {
        for (Map.Entry<String,Object> bad : Map.<String,Object>of("trustBundleVersion",2L,"trustBundleSha256","e".repeat(64),
                "rootFingerprint","f".repeat(64),"trustDomain","other.domain").entrySet()) {
            var data=report();data.put(bad.getKey(),bad.getValue());
            assertEquals(OtaDeviceEligibilityEvaluator.Reason.TRUST_NOT_ACKNOWLEDGED,evaluate(data,manifest()));
        }
    }
    /** 目标安全下限、计数器容量、模型来源与manifest资源不可绕过。 */
    @Test void rejectsManifestOutsideIntersection() throws Exception {
        for (Map.Entry<String,Object> bad : Map.<String,Object>ofEntries(Map.entry("securityVersion",0L),
                Map.entry("artifactSize",67_108_865L),Map.entry("bootloaderMinimumVersion","1.4.0"),
                Map.entry("allowedSourceThingModelVersionIds",List.of("00000000-0000-0000-0000-000000000099"))).entrySet()) {
            var target=manifest();target.put(bad.getKey(),bad.getValue());
            assertEquals(OtaDeviceEligibilityEvaluator.Reason.INCOMPATIBLE,evaluate(report(),target));
        }
        var data=report();data.put("protectedSecurityCounterBits",1L);
        var target=manifest();target.put("securityVersion",2L);
        assertEquals(OtaDeviceEligibilityEvaluator.Reason.INCOMPATIBLE,evaluate(data,target));
    }
    /** NONE固件不能借助零额外Flash要求越过实际可用空间，等长边界可通过。 */
    @Test
    void artifactMustFitAvailableFlashEvenWhenManifestMinimumIsZero() throws Exception {
        var data = report();
        data.put("availableFlashBytes", 1023L);
        var target = manifest();
        var requirements = new LinkedHashMap<>(OtaTrustBundleCodec.object(target.get("requirements")));
        requirements.put("minimumFlashBytes", 0L);
        target.put("requirements", requirements);
        assertEquals(OtaDeviceEligibilityEvaluator.Reason.INCOMPATIBLE, evaluate(data, target));
        data.put("availableFlashBytes", 1024L);
        assertEquals(OtaDeviceEligibilityEvaluator.Reason.ELIGIBLE, evaluate(data, target));
    }

    /** 独立合同组合，真实签名及权威锁由调用者专项验真。 */
    private OtaDeviceEligibilityEvaluator.Reason evaluate(Map<String,Object> report,Map<String,Object> manifest) throws Exception {
        return evaluator.evaluate(codec.decode(json.writeObject(report)),new OtaTypeBaselineCodec().decode(resource("baseline/type-baseline-v1.json")),
                json.writeObject(manifest),1,"d".repeat(64),"a".repeat(64),"test.domain");
    }
    /** 创建可变公开报告。 */
    private Map<String,Object> report() throws Exception {return new LinkedHashMap<>(json.parseObject(resource("device-report-v1.json")));}
    /** 保留完整合法manifest结构，调整到公开基线身份。 */
    private Map<String,Object> manifest() throws Exception {
        var m=new LinkedHashMap<>(json.parseObject(resource("manifest-v1.json")));
        m.put("deviceTypeId","00000000-0000-0000-0000-000000000003");m.put("productKey","test_product");m.put("trustDomain","test.domain");
        m.put("hardware",Map.of("model","board.v1","boardRevisionMin",0L,"boardRevisionMax",3L));
        m.put("allowedSourceThingModelVersionIds",List.of("00000000-0000-0000-0000-000000000004"));return m;
    }
    /** 读取公开固定向量。 */
    private static byte[] resource(String name) throws Exception {
        try(var input=OtaDeviceReportContractTests.class.getResourceAsStream("/ota/"+name)){assertNotNull(input);return input.readAllBytes();}
    }
}
