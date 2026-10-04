package com.things.link.dashboard.application.publication;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PostgreSQL对规范Schema执行{@code jsonb::text}所得的权威表示。
 *
 * @param text 数据库返回的规范JSON文本
 * @param digestAlgorithm 摘要算法合同标识
 * @param digest 对text的UTF-8字节计算所得小写SHA-256
 */
public record PostgreSqlDashboardSchemaCanonicalForm(
        String text, String digestAlgorithm, String digest) {

    /** 发布Schema唯一允许的摘要算法。 */
    public static final String DIGEST_ALGORITHM = "PG_JSONB_TEXT_V1_SHA256";
    /** 数据库迁移与Schema合同共同冻结的规范文本最大字节数。 */
    public static final int MAXIMUM_UTF8_BYTES = 512_000;
    /** SHA-256小写十六进制形状。 */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** 冻结数据库结果并在应用边界复核算法、摘要形状和独立的PG文本字节上限。 */
    public PostgreSqlDashboardSchemaCanonicalForm {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        if (!DIGEST_ALGORITHM.equals(digestAlgorithm)) {
            throw new IllegalArgumentException("看板Schema摘要算法不符合冻结合同");
        }
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("看板Schema摘要必须是小写SHA-256");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new IllegalArgumentException("PostgreSQL规范Schema超过512000字节");
        }
    }

    /**
     * 返回参与数据库摘要的精确UTF-8字节副本。
     *
     * @return 每次新建的规范文本字节
     */
    public byte[] utf8() {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
