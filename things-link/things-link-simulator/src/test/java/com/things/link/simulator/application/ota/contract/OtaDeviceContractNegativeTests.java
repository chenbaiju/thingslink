package com.things.link.simulator.application.ota.contract;

import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaSignatureProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 负向测试：设备侧编解码器必须在平台会拒绝的地方失败，并且对非规范字节比平台更严格。
 *
 * <p>设备是较弱的一方：如果它接受平台永远发不出来的歧义报文，或者自己发出平台解不开的字节，
 * 失败会推迟到联调现场且难以定位。因此这里逐一钉死缺失字段、未知字段、版本漂移、{@code null}、
 * 重复字段与非规范排序。</p>
 *
 * <p>其中「非规范排序」一节同时断言平台的宽松行为，用来把设备侧的有意严格性写成可审计的偏差，
 * 而不是让它悄悄变成两种实现的偶然差异。</p>
 */
@DisplayName("S13-4d-2a 设备侧线合同失败关闭")
class OtaDeviceContractNegativeTests {

    /** 平台下载申请解码器，用于对照平台对非规范输入的实际行为。 */
    private static final OtaDownloadRequestCodec PLATFORM_REQUEST = new OtaDownloadRequestCodec();

    /** 平台下载响应编解码器，用于对照非规范清单的拒绝行为。 */
    private static final OtaDownloadResponseCodec PLATFORM_RESPONSE = new OtaDownloadResponseCodec();

    /** 合法下载申请的规范字节，供各负向用例修改。 */
    private static final byte[] VALID_REQUEST = OtaDeviceDownloadRequestCodec.encode(
            new OtaDeviceDownloadRequestCodec.Request(OtaDeviceDownloadRequestCodec.CONTRACT_VERSION,
                    UUID.fromString("01920000-0000-7000-8000-000000000001"),
                    UUID.fromString("01920000-0000-7000-8000-000000000002"), 2,
                    OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8))));

    /** 合法提交许可的规范字节，供各负向用例修改。 */
    private static final byte[] VALID_COMMIT_PERMIT = OtaDeviceCommitPermitCodec.encode(
            new OtaDeviceCommitPermitCodec.Permit(OtaDeviceCommitPermitCodec.CONTRACT_VERSION,
                    UUID.fromString("01920000-0000-7000-8000-000000000011"),
                    UUID.fromString("01920000-0000-7000-8000-000000000012"), 1,
                    UUID.fromString("01920000-0000-7000-8000-000000000013"),
                    OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                    UUID.fromString("01920000-0000-7000-8000-000000000014"), 7L,
                    OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)), "B",
                    1_800_000_000L));

    /** 合法提交回执的规范字节，供各负向用例修改。 */
    private static final byte[] VALID_COMMIT_RECEIPT = OtaDeviceCommitReceiptCodec.encode(
            new OtaDeviceCommitReceiptCodec.Receipt(OtaDeviceCommitReceiptCodec.CONTRACT_VERSION,
                    UUID.fromString("01920000-0000-7000-8000-000000000021"), 1,
                    UUID.fromString("01920000-0000-7000-8000-000000000022"),
                    OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                    UUID.fromString("01920000-0000-7000-8000-000000000023"),
                    UUID.fromString("01920000-0000-7000-8000-000000000024"),
                    UUID.fromString("01920000-0000-7000-8000-000000000025"),
                    OtaWireTestVectors.committedEvidence(
                            OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)), 1024L)));

    /** 缺失字段：闭集同时拒绝缺失与未知。 */
    @Test
    void missingFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_REQUEST);
        fields.remove("manifestSha256");

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 未知字段：设备不能把平台不会发送的字段当成扩展点。 */
    @Test
    void unknownFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_REQUEST);
        fields.put("deviceKey", "attacker-device");

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 版本漂移：设备不能把 v2 报文当 v1 处理。 */
    @Test
    void wrongContractVersionFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_REQUEST);
        fields.put("contractVersion", "tc-ota-download-request/v2");

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** {@code null} 不是合法值，规范 JSON 解析层就必须拒绝。 */
    @Test
    void nullFieldFailsClosed() {
        String payload = "{\"contractVersion\":null,\"jobId\":\"01920000-0000-7000-8000-000000000002\","
                + "\"manifestSha256\":\"" + OtaWireTestVectors.sha256Hex("m".getBytes(StandardCharsets.UTF_8))
                + "\",\"requestId\":\"01920000-0000-7000-8000-000000000001\",\"attemptNo\":2}";

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 重复字段：同名出现两次会让不同实现取到不同值，必须拒绝。 */
    @Test
    void duplicateFieldFailsClosed() {
        String payload = new String(VALID_REQUEST, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"attemptNo\":2,");

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 非规范排序：设备侧拒绝，平台侧接受并重新规范化。
     *
     * <p>这条偏差是有意的：规范字节同时是清单摘要与签名的输入，接受等价但不同字节的编码会让
     * 「同一事实」拥有多种可签名字节。设备只信平台实际会产生的形态。</p>
     */
    @Test
    void nonCanonicalOrderingIsRejectedByDeviceButToleratedByPlatform() {
        byte[] reordered = ("{\"requestId\":\"01920000-0000-7000-8000-000000000001\","
                + "\"attemptNo\":2,"
                + "\"manifestSha256\":\"" + OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8))
                + "\","
                + "\"contractVersion\":\"tc-ota-download-request/v1\","
                + "\"jobId\":\"01920000-0000-7000-8000-000000000002\"}").getBytes(StandardCharsets.UTF_8);

        assertThat(new String(reordered, StandardCharsets.UTF_8))
                .isNotEqualTo(new String(VALID_REQUEST, StandardCharsets.UTF_8));
        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(reordered))
                .isInstanceOf(IllegalArgumentException.class);
        // 平台接受同一逻辑内容的非规范字节，并把它重新规范化为设备期望的字节。
        assertThat(PLATFORM_REQUEST.decode(reordered).canonical()).isEqualTo(VALID_REQUEST);
    }

    /** 空白同样属于非规范形态。 */
    @Test
    void insignificantWhitespaceFailsClosed() {
        byte[] padded = (" " + new String(VALID_REQUEST, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.decode(padded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 进度合同只接受平台闭集的四个阶段：下载阶段不能借用本协议上报。 */
    @Test
    void progressEncoderRejectsStageOutsidePlatformClosedSet() {
        OtaDeviceJobProgressCodec.Progress progress = new OtaDeviceJobProgressCodec.Progress(
                OtaDeviceJobProgressCodec.CONTRACT_VERSION, UUID.randomUUID(), 1, 1L, UUID.randomUUID(),
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)), "DOWNLOADING",
                UUID.randomUUID(),
                OtaWireTestVectors.evidenceForStage("VERIFYING",
                        OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)), 1024L));

        assertThatThrownBy(() -> OtaDeviceJobProgressCodec.encode(progress))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 健康时长不得大于运行时长；编码器不能把矛盾事实写进合同。 */
    @Test
    void healthEncoderRejectsHealthyForExceedingUptime() {
        OtaDeviceHealthCodec.Health health = new OtaDeviceHealthCodec.Health(OtaDeviceHealthCodec.CONTRACT_VERSION,
                UUID.randomUUID(), 1, UUID.randomUUID(),
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)), 1L, UUID.randomUUID(),
                new OtaDeviceHealthCodec.HealthEvidence(OtaWireTestVectors.evidenceForStage("HEALTH_CHECKING",
                        OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)), 1024L),
                        10L, 11L));

        assertThatThrownBy(() -> OtaDeviceHealthCodec.encode(health))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 类型化输入为 {@code null} 时必须失败关闭，而不是产生空指针或空报文。 */
    @Test
    void nullTypedInputFailsClosed() {
        assertThatThrownBy(() -> OtaDeviceDownloadRequestCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceJobProgressCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceHealthCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceAvailableNotificationCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceInstallStopOperationCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceInstallStopReportCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceInstallStopStatusReportCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 下载响应的清单必须已经是规范字节：设备侧编码器与平台编码器都必须拒绝非规范清单。
     */
    @Test
    void nonCanonicalManifestIsRejectedByDeviceAndPlatformEncoders() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(OtaWireTestVectors.artifact(64));
        String canonicalText = new String(manifest.canonical(), StandardCharsets.UTF_8);
        byte[] nonCanonical = canonicalText.replaceFirst("\\}$", " }").getBytes(StandardCharsets.UTF_8);
        OtaDeviceDownloadResponseCodec.Response device = new OtaDeviceDownloadResponseCodec.Response(
                OtaDeviceDownloadResponseCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), manifest.firmwareId(), 1, manifest.sha256(), nonCanonical, manifest.signature(),
                manifest.publicKeySpki(), "TC_OTA_ED25519_V1", manifest.keyFingerprint(),
                URI.create("https://storage.example/object"), OtaWireTestVectors.EXPIRES_AT);

        assertThatThrownBy(() -> new OtaDeviceDownloadResponseCodec().encode(device))
                .isInstanceOf(IllegalArgumentException.class);
        OtaDownloadResponseCodec.Response platform = new OtaDownloadResponseCodec.Response(
                "tc-ota-download-response/v1", device.authorizationId(), device.requestId(), device.campaignId(),
                device.jobId(), device.firmwareId(), device.attemptNo(), device.manifestSha256(), nonCanonical,
                manifest.signature(), manifest.publicKeySpki(), OtaSignatureProfile.ED25519_V1,
                manifest.keyFingerprint(), device.downloadUrl(), device.expiresAt());
        assertThatThrownBy(() -> PLATFORM_RESPONSE.encode(platform))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 下载响应版本漂移：设备侧与平台侧都必须拒绝 v2。 */
    @Test
    void downloadResponseWrongContractVersionFailsClosed() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(64);
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
        OtaDeviceDownloadResponseCodec.Response device = new OtaDeviceDownloadResponseCodec.Response(
                "tc-ota-download-response/v2", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), manifest.firmwareId(), 1, manifest.sha256(), manifest.canonical(),
                manifest.signature(), manifest.publicKeySpki(), "TC_OTA_ED25519_V1", manifest.keyFingerprint(),
                URI.create("https://storage.example/object"), OtaWireTestVectors.EXPIRES_AT);

        assertThatThrownBy(() -> new OtaDeviceDownloadResponseCodec().encode(device))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 非规范外层字节：设备侧拒绝，平台侧重新规范化接受。 */
    @Test
    void nonCanonicalDownloadResponseBytesRejectedByDevice() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(OtaWireTestVectors.artifact(64));
        OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, UUID.randomUUID(),
                UUID.randomUUID(), 1, URI.create("https://storage.example/object"));
        byte[] canonicalBytes = new OtaDeviceDownloadResponseCodec().encode(pair.device());
        byte[] padded = (" " + new String(canonicalBytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new OtaDeviceDownloadResponseCodec().decode(padded))
                .isInstanceOf(IllegalArgumentException.class);
        // 平台接受同一逻辑内容的非规范字节，并在重新编码时恢复为规范字节。
        assertThat(PLATFORM_RESPONSE.encode(PLATFORM_RESPONSE.decode(padded))).isEqualTo(canonicalBytes);
    }

    /** 提交许可缺失字段：闭集同时拒绝缺失与未知，设备不得补默认值。 */
    @Test
    void commitPermitMissingFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_PERMIT);
        fields.remove("bootId");

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可未知字段：设备不能把平台不会签发的字段当成扩展点。 */
    @Test
    void commitPermitUnknownFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_PERMIT);
        fields.put("deviceKey", "attacker-device");

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可重复字段：同名出现两次会让不同实现取到不同值，必须拒绝。 */
    @Test
    void commitPermitDuplicateFieldFailsClosed() {
        String payload = new String(VALID_COMMIT_PERMIT, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"attemptNo\":1,");

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可版本漂移：设备不能把 v2 许可当 v1 消费。 */
    @Test
    void commitPermitWrongContractVersionFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_PERMIT);
        fields.put("contractVersion", "tc-ota-commit-permit/v2");

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可类型错误：字符串数字不是整数，规范合同只接受真实 JSON 整数。 */
    @Test
    void commitPermitWrongTypeFailsClosed() {
        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(
                new String(VALID_COMMIT_PERMIT, StandardCharsets.UTF_8)
                        .replace("\"attemptNo\":1", "\"attemptNo\":\"1\"")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可槽位越界：许可层只接受 A/B，SINGLE 不是合法许可目标。 */
    @Test
    void commitPermitIllegalSlotFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_PERMIT);
        fields.put("targetSlot", "C");

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交许可非规范字节：字段顺序不同即拒绝，设备只信平台实际会签发的字节形态。 */
    @Test
    void commitPermitNonCanonicalOrderingFailsClosed() {
        byte[] reordered = ("{\"expiresAt\":1800000000,\"targetSlot\":\"B\",\"artifactSha256\":\""
                + OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8))
                + "\",\"bootId\":\"01920000-0000-7000-8000-000000000014\",\"securityVersion\":7,"
                + "\"manifestSha256\":\"" + OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8))
                + "\",\"authorizationId\":\"01920000-0000-7000-8000-000000000013\",\"attemptNo\":1,"
                + "\"jobId\":\"01920000-0000-7000-8000-000000000012\","
                + "\"permitId\":\"01920000-0000-7000-8000-000000000011\","
                + "\"contractVersion\":\"tc-ota-commit-permit/v1\"}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> OtaDeviceCommitPermitCodec.decode(reordered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交回执缺失字段：回执九字段必须全部存在，设备不得省略许可或授权身份。 */
    @Test
    void commitReceiptMissingFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_RECEIPT);
        fields.remove("permitId");

        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交回执未知顶层字段：平台闭集只接受九字段，设备不得夹带额外身份。 */
    @Test
    void commitReceiptUnknownFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_RECEIPT);
        fields.put("deviceKey", "attacker-device");

        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交回执证据缺失字段：证据复用十九字段闭集，缺一即拒绝。 */
    @Test
    void commitReceiptMissingEvidenceFieldFailsClosed() {
        Map<String, Object> fields = mutableFields(VALID_COMMIT_RECEIPT);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = new LinkedHashMap<>((Map<String, Object>) fields.get("evidence"));
        evidence.remove("watchdogHealthy");
        fields.put("evidence", evidence);

        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.decode(canonical(fields)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交回执类型错误：字符串尝试号必须失败关闭。 */
    @Test
    void commitReceiptWrongTypeFailsClosed() {
        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.decode(
                new String(VALID_COMMIT_RECEIPT, StandardCharsets.UTF_8)
                        .replace("\"attemptNo\":1", "\"attemptNo\":\"1\"")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交回执非规范字节：设备侧比平台更严格，只接受自己实际会产生的规范形态。 */
    @Test
    void commitReceiptNonCanonicalBytesFailsClosed() {
        byte[] padded = (" " + new String(VALID_COMMIT_RECEIPT, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> OtaDeviceCommitReceiptCodec.decode(padded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 把规范字节解析成可修改的字段映射。 */
    private static Map<String, Object> mutableFields(byte[] canonicalBytes) {
        return new LinkedHashMap<>(new OtaCanonicalJson().parseObject(canonicalBytes));
    }

    /** 把字段映射重新编码为规范字节。 */
    private static byte[] canonical(Map<String, Object> fields) {
        return new OtaCanonicalJson().writeObject(fields);
    }
}
