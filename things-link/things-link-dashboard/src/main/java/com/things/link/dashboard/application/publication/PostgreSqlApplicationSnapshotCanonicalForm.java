package com.things.link.dashboard.application.publication;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PostgreSQL对完整ApplicationSnapshot执行{@code jsonb::text}所得的权威表示。
 *
 * @param text 数据库返回的规范JSON文本
 * @param digestAlgorithm 应用快照摘要算法合同标识
 * @param digest 对规范文本UTF-8字节计算的小写SHA-256
 */
public record PostgreSqlApplicationSnapshotCanonicalForm(
        String text, String digestAlgorithm, String digest) {

    /** 应用快照唯一允许的摘要算法。 */
    public static final String DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 应用快照PostgreSQL规范文本的最大UTF-8字节数。 */
    public static final int MAXIMUM_UTF8_BYTES = 65_536;
    /** SHA-256小写十六进制表示的精确语法。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 冻结数据库结果并独立执行应用快照的64KiB规范文本限制。 */
    public PostgreSqlApplicationSnapshotCanonicalForm {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        if (!DIGEST_ALGORITHM.equals(digestAlgorithm)) {
            throw new IllegalArgumentException("应用快照摘要算法不符合冻结合同");
        }
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("应用快照摘要必须是小写SHA-256");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new ApplicationPublicationQualificationException(
                    ApplicationPublicationQualificationException.Reason.SNAPSHOT_TOO_LARGE);
        }
    }

    /** @return 参与数据库摘要的精确UTF-8字节副本 */
    public byte[] utf8() {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
