package com.things.link.shared.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 出站主机安全校验：保留段矩阵与两条门禁（发送时权威 / 落库时字面）的边界测试。 */
@DisplayName("出站主机安全校验")
class SafeOutboundHostTests {

    @ParameterizedTest
    @ValueSource(strings = {
            // IPv4：环回 / any-local / RFC1918 / CGNAT / 链路本地+元数据 / IETF 协议分配 / TEST-NET /
            //       废弃 6to4 中继 / 基准测试 / 组播+广播
            "127.0.0.1", "127.255.255.255",
            "0.0.0.0",
            "10.0.0.1",
            "172.16.0.1", "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254", "169.254.1.1",
            "100.64.0.1", "100.127.255.255",
            "192.0.0.1", "192.0.0.9", "192.0.0.170", "192.0.0.255",
            "192.0.2.1", "198.51.100.1", "203.0.113.1",
            "192.88.99.1", "192.88.99.255",
            "198.18.0.1", "198.19.255.255",
            "224.0.0.1", "255.255.255.255",
            // IPv6：未指定 / 环回 / 唯一本地 / 链路本地 / 废弃站本地 / 组播 / NAT64 前缀 /
            //       discard-only / IETF 协议分配 / 6to4
            "::", "::1",
            "fc00::1", "fd00::1",
            "fe80::1", "fec0::1",
            "ff02::1", "2001:db8::1",
            "64:ff9b::1", "64:ff9b::ffff:ffff",
            "64:ff9b:1::1", "64:ff9b:1::ffff:ffff",
            "100::1", "100::ffff",
            "2001::1", "2001:2::1", "2001:20::1",
            "2002::1", "2002:ffff:ffff::1",
            // IPv4-mapped / compatible IPv6 必须解包到内嵌 IPv4 后判定（含新覆盖段）
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::127.0.0.1",
            "::ffff:192.0.0.9", "::ffff:198.18.0.1"
    })
    @DisplayName("保留/内网/链路本地段一律判定 forbidden")
    void forbidsReservedRanges(String literal) throws Exception {
        assertThat(SafeOutboundHost.isForbidden(InetAddress.getByName(literal)))
                .as(literal)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 公共 IPv4 与紧邻保留段外侧的边界值（含新覆盖段边界）
            "8.8.8.8", "1.1.1.1", "93.184.216.34",
            "172.15.0.1", "172.32.0.1",
            "100.63.0.1", "100.128.0.1",
            "169.255.0.1", "192.169.0.1", "191.255.255.255",
            "192.0.1.1", "192.88.98.255", "192.88.100.0",
            "198.17.255.255", "198.20.0.0",
            // 公共 IPv6 与 mapped 到公共 IPv4 的形态，以及新覆盖段外侧边界
            "2001:4860:4860::8888", "2606:4700:4700::1111",
            "::ffff:8.8.8.8",
            "64:ff9b:2::1", "2001:200::1", "2003::1"
    })
    @DisplayName("公共地址放行")
    void allowsPublicRanges(String literal) throws Exception {
        assertThat(SafeOutboundHost.isForbidden(InetAddress.getByName(literal)))
                .as(literal)
                .isFalse();
    }

    @Test
    @DisplayName("落库门禁：字面内网 IP 拒绝，hostname 与数字形态跳过（不触发 DNS）")
    void literalGateRejectsInternalLiteralAndSkipsHostnames() {
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://127.0.0.1/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://169.254.169.254/latest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://[::1]/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://[::ffff:127.0.0.1]/")))
                .isInstanceOf(IllegalArgumentException.class);
        // 复核补齐的特殊用途网段也必须在落库门禁被字面拦截
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://192.0.0.9/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://198.18.0.1/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://[64:ff9b::1]/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://[64:ff9b:1::1]/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://[2002::1]/")))
                .isInstanceOf(IllegalArgumentException.class);

        // hostname 与十进制数字形态交发送时权威校验，落库门禁不阻塞、不触发 DNS
        assertThatCode(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://example.com/")))
                .doesNotThrowAnyException();
        assertThatCode(() -> SafeOutboundHost.requireNoForbiddenLiteral(URI.create("https://2130706433/")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("发送门禁：内网/回环/非 https 拒绝，公共 host 放行")
    void publicTargetGateRejectsInternalAndAllowsPublic() throws Exception {
        assertThatThrownBy(() -> SafeOutboundHost.requirePublicTarget(URI.create("https://127.0.0.1/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requirePublicTarget(URI.create("https://169.254.169.254/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requirePublicTarget(URI.create("https://[::1]/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeOutboundHost.requirePublicTarget(URI.create("http://example.com/")))
                .isInstanceOf(IllegalArgumentException.class);
        // 回环 hostname 经 DNS 解析后命中 127/::1，发送时权威校验兜住
        assertThatThrownBy(() -> SafeOutboundHost.requirePublicTarget(URI.create("https://localhost/")))
                .isInstanceOf(IllegalArgumentException.class);

        // 公共 IP 字面量无需 DNS 即放行
        assertThatCode(() -> SafeOutboundHost.requirePublicTarget(URI.create("https://8.8.8.8/")))
                .doesNotThrowAnyException();
    }
}
