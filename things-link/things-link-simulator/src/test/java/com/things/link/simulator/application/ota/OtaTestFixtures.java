package com.things.link.simulator.application.ota;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 测试用固定事实：确定性 artifact、摘要与赋值对象构造。
 *
 * <p>所有场景共用同一套「怎么造 artifact、怎么算摘要」规则，避免不同用例各自拼字节导致
 * 「摘要不符」和「长度不符」互相污染。</p>
 */
final class OtaTestFixtures {

    /** 所有场景共用的确定性起点时刻。 */
    static final Instant START = Instant.parse("2026-09-12T00:00:00Z");

    /** 所有场景共用的下载总预算。 */
    static final Duration BUDGET = Duration.ofMinutes(5);

    /** 工具类不允许实例化。 */
    private OtaTestFixtures() {
    }

    /**
     * 生成确定性 artifact。
     *
     * @param size 字节数
     * @return 可重复生成的字节序列
     */
    static byte[] artifact(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) ((index * 31 + 7) & 0xFF);
        }
        return bytes;
    }

    /**
     * 生成与 {@link #artifact(int)} 同长度但内容不同的 artifact，用于摘要不符场景。
     *
     * @param size 字节数
     * @return 不同的字节序列
     */
    static byte[] otherArtifact(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) ((index * 17 + 3) & 0xFF);
        }
        return bytes;
    }

    /**
     * 计算字节数组的 SHA-256。
     *
     * @param data 字节
     * @return 小写十六进制摘要
     */
    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    /**
     * 计算字节数组前缀的 SHA-256。
     *
     * @param data 字节
     * @param length 前缀长度
     * @return 小写十六进制摘要
     */
    static String sha256Hex(byte[] data, int length) {
        MessageDigest digest = newDigest();
        digest.update(data, 0, length);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 计算文本的 SHA-256，用于构造清单摘要等固定身份。
     *
     * @param text 文本
     * @return 小写十六进制摘要
     */
    static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造与给定 artifact 完全匹配的下载赋值。
     *
     * @param uri 下载地址
     * @param artifact 目标 artifact
     * @return 不可变赋值
     */
    static OtaDownloadAssignment assignmentFor(URI uri, byte[] artifact) {
        return new OtaDownloadAssignment(UUID.randomUUID(), UUID.randomUUID(), 1,
                sha256Hex("manifest"), sha256Hex(artifact), artifact.length, uri);
    }

    /** 创建 SHA-256 摘要器。 */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行时不提供 SHA-256", failure);
        }
    }
}
