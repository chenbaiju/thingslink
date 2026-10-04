package com.things.link.ota.application;

import java.util.Set;
import java.util.UUID;

/** 设备下载申请的闭集身份，不携带自报设备身份或临时下载地址。 */
public final class OtaDownloadRequestCodec {
    /** 输入字节的独立业务上限。 */
    private static final int MAX_BYTES = 2048;
    /** 唯一允许的五个字段。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion", "requestId", "jobId", "attemptNo", "manifestSha256");
    /** 共享受限规范JSON解析器。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 校验原始字节上限、字段与语义，并生成唯一规范摘要。 */
    public Decoded decode(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) {
            throw new IllegalArgumentException("OTA下载申请合同不合法");
        }
        var fields = json.parseObject(input);
        OtaTrustBundleCodec.closed(fields, FIELDS);
        if (!"tc-ota-download-request/v1".equals(fields.get("contractVersion"))) {
            throw new IllegalArgumentException("OTA下载申请合同不合法");
        }
        var request = new Request("tc-ota-download-request/v1",
                OtaTrustBundleCodec.uuid(fields.get("requestId")),
                OtaTrustBundleCodec.uuid(fields.get("jobId")),
                (int) OtaTrustBundleCodec.integer(fields.get("attemptNo"), 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.text(fields.get("manifestSha256"), "[0-9a-f]{64}"));
        byte[] canonical = json.writeObject(fields);
        return new Decoded(request, canonical, OtaTrustBundleCodec.sha256(canonical));
    }

    /** 完整请求身份，其幂等范围由可信连接身份与持久服务决定。
     * @param contractVersion 固定协议版本
     * @param requestId 设备生成的请求标识
     * @param jobId 已派发作业
     * @param attemptNo 当前作业尝试号
     * @param manifestSha256 已签名清单摘要
     */
    public record Request(String contractVersion, UUID requestId, UUID jobId,
                          int attemptNo, String manifestSha256) { }

    /** 已解析的不可变合同投影。
     * @param value 类型化申请
     * @param canonical 规范JSON字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Request value, byte[] canonical, String sha256) {
        /** 防止调用者修改内部规范字节。 */
        public Decoded { canonical = canonical.clone(); }
        /** 返回独立字节副本。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
}
