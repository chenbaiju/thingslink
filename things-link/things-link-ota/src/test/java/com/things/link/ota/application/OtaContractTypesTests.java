package com.things.link.ota.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTA冻结合同类型测试。
 *
 * <p>本片没有签名实现；这里只钉住ADR0053进入未来manifest和设备协议的稳定标识，
 * 防止实现阶段改用任意算法名或重解释既有版本。</p>
 */
@DisplayName("OTA冻结合同类型")
class OtaContractTypesTests {
    /** manifest V1必须保持ADR0053冻结值。 */
    @Test
    @DisplayName("manifest V1使用冻结协议值")
    void manifestVersionOneUsesFrozenProtocolValue() {
        assertThat(OtaManifestContractVersion.V1.value()).isEqualTo("tc-ota-manifest/v1");
    }

    /** 签名Profile只能暴露ADR0053允许的两个稳定值。 */
    @Test
    @DisplayName("只登记两个允许的签名Profile")
    void onlyFrozenSignatureProfilesAreRegistered() {
        assertThat(OtaSignatureProfile.values())
                .extracting(OtaSignatureProfile::value)
                .containsExactly("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");
    }
}
