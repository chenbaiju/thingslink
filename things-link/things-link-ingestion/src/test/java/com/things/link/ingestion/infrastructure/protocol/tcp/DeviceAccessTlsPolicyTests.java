package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import com.things.link.testing.tls.TestTlsMaterial;
import java.nio.file.Path;
import org.springframework.core.io.Resource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备面 TCP 的传输策略固定（负责人 2026-09-18 裁决②）：协议下限与密码套件白名单必须可断言、可 fail-closed。
 *
 * <p>这些参数不是"写在文档里的约定"而是套在监听上的实际值，因此必须由用例钉住：一旦有人放宽协议或加回 CBC／
 * 静态 RSA 套件，这里就该红。用例使用仓库内的仅测试用自签材料，不触碰生产证书。</p>
 */
class DeviceAccessTlsPolicyTests {

    /** 仅测试使用的自签证书。 */
    private static final Resource CERTIFICATE = new FileSystemResource(material().resolve(TestTlsMaterial.CERT));

    /** 仅测试使用的 PKCS#8 私钥。 */
    private static final Resource PRIVATE_KEY = new FileSystemResource(material().resolve(TestTlsMaterial.KEY));

    /** 白名单固定为 TLS 1.3 与 TLS 1.2，且 TLS 1.2 必须在位（设备兼容下限）。 */
    @Test
    void pinnedParametersKeepTls12FloorAndTls13Ceiling() {
        SSLParameters parameters = new DeviceAccessTlsContextFactory()
                .pinnedServerParameters(context());

        assertThat(Arrays.asList(parameters.getProtocols())).containsExactly("TLSv1.3", "TLSv1.2");
        assertThat(DeviceAccessTlsContextFactory.allowedProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
    }

    /** 密码套件只允许白名单内的 AEAD 套件，且不接受客户端证书。 */
    @Test
    void pinnedParametersOnlyEnableWhitelistedAeadSuites() {
        SSLParameters parameters = new DeviceAccessTlsContextFactory()
                .pinnedServerParameters(context());

        List<String> allowed = DeviceAccessTlsContextFactory.allowedCipherSuites();
        assertThat(parameters.getCipherSuites()).isNotEmpty().allMatch(allowed::contains);
        assertThat(parameters.getCipherSuites()).allSatisfy(suite ->
                assertThat(suite).as("白名单不得包含非 AEAD 或静态 RSA 密钥交换套件")
                        .matches(".*(_GCM_|_CHACHA20_POLY1305_).*"));
        assertThat(parameters.getWantClientAuth()).isFalse();
        assertThat(parameters.getNeedClientAuth()).isFalse();
    }

    private static Path material() {
        try { return TestTlsMaterial.ensure(TestTlsMaterial.checkoutRoot(Path.of(""))); }
        catch (Exception failure) { throw new IllegalStateException("Test TLS material unavailable", failure); }
    }

    /** @return 由测试材料构建的上下文 */
    private static SSLContext context() {
        return new DeviceAccessTlsContextFactory().create(CERTIFICATE, PRIVATE_KEY, null);
    }
}
