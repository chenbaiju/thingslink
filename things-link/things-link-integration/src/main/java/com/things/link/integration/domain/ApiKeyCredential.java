package com.things.link.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** ADR0169：256位随机秘密；规范解析、摘要匹配和诊断脱敏，不持有认证资格。 */
public final class ApiKeyCredential {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile(
            "tcak1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}");
    private final UUID keyId;
    private final String value;

    private ApiKeyCredential(UUID keyId, String value) {
        this.keyId = keyId;
        this.value = value;
    }

    /** 只在首次成功签发事务使用；数据库失败时丢弃，不缓存或记录。 */
    public static ApiKeyCredential generate(UUID keyId) {
        if (keyId == null) throw new IllegalArgumentException("Key身份不能为空");
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String value = "tcak1." + keyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        java.util.Arrays.fill(secret, (byte) 0);
        return new ApiKeyCredential(keyId, value);
    }

    /** 不接受尾部填充位别名，确保完整凭据只有一种词法。错误不回显输入。 */
    public static Optional<ApiKeyCredential> parse(String input) {
        if (input == null || input.length() != 86 || !FORMAT.matcher(input).matches()) return Optional.empty();
        String secret = input.substring(43);
        byte[] bytes = Base64.getUrlDecoder().decode(secret);
        boolean canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(secret);
        java.util.Arrays.fill(bytes, (byte) 0);
        if (!canonical) return Optional.empty();
        return Optional.of(new ApiKeyCredential(UUID.fromString(input.substring(6, 42)), input));
    }

    public UUID keyId() { return keyId; }

    /** 仅首次响应的显式秘密出口；禁止用于日志、审计或普通元数据DTO。 */
    public String reveal() { return value; }

    /** 仅仓储使用的算法版本1摘要；不返回管理查询。 */
    public String digest() {
        return HexFormat.of().formatHex(sha256(value));
    }

    /** 已知Key摘要匹配；成功仍须由调用方检查当前权限/期限/代次/IP。 */
    public boolean matchesDigest(String expected) {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(sha256(value), HexFormat.of().parseHex(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用", impossible);
        }
    }

    @Override
    public String toString() { return "ApiKeyCredential[REDACTED]"; }
}
