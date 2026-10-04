package com.things.link.ota.application;

/**
 * OTA发布签名Profile白名单。
 *
 * <p>ADR0053第2节禁止把任意JCA/OpenSSL算法名透传到设备；新增算法必须增加
 * 版本化Profile和ADR，不能改变既有枚举语义。</p>
 */
public enum OtaSignatureProfile {
    /** Ed25519与RFC8410 SPKI、原始64字节签名。 */
    ED25519_V1("TC_OTA_ED25519_V1"),
    /** P-256 SPKI、SHA-256与固定64字节low-S P1363签名。 */
    ES256_P1363_V1("TC_OTA_ES256_P1363_V1");

    /** manifest中持久化和签名的稳定Profile值。 */
    private final String value;

    /**
     * 创建允许的签名Profile。
     *
     * @param value 稳定Profile值
     */
    OtaSignatureProfile(String value) {
        this.value = value;
    }

    /**
     * 返回稳定Profile值。
     *
     * @return manifest使用的Profile标识
     */
    public String value() {
        return value;
    }
}
