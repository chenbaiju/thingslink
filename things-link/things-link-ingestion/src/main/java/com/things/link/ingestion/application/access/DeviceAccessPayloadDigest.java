package com.things.link.ingestion.application.access;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 接入业务载荷的完整性摘要。
 *
 * <p>摘要用于区分「同一次尝试的重放」与「同一个 messageId 换了载荷」：只有对**接入面收到的原始字节**
 * 求摘要，两台设备用不同空白或字段顺序表达同一意图时才会被判成不同载荷并被拒绝，而不是被规范化悄悄抹平。
 * 同一份字节在任何协议上都必须得到同一摘要，因此摘要只依赖字节内容，不掺协议、时间或设备标识。</p>
 */
final class DeviceAccessPayloadDigest {

    /** 工具类不允许实例化。 */
    private DeviceAccessPayloadDigest() {
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param payload 接入面收到的业务载荷字节
     * @return 64 位小写十六进制摘要
     */
    static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }
}
