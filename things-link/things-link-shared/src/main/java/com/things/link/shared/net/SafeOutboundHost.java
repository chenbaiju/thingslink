package com.things.link.shared.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 出站主机安全校验：拒绝环回/内网/链路本地/组播/保留段/特殊用途网段等 SSRF 目标。
 *
 * <p>判定以 IANA IPv4/IPv6 特殊用途地址注册表（RFC 6890）为基线，对本地翻译、保留、
 * 非全球可达以及可能形成内部路由通道的前缀采取保守拒绝，而非只拦「非全球可达」条目：IPv4 覆盖
 * any-local、RFC1918、CGNAT(100.64/10)、loopback、链路本地(169.254/16)、IETF 协议分配(192.0.0/24)、
 * TEST-NET-1/2/3、废弃 6to4 中继(192.88.99/24)、基准测试(198.18/15)、组播/保留 class E/广播；
 * IPv6 覆盖 unspecified、loopback、ULA(fc00::/7)、链路本地/废弃站本地、组播、NAT64 前缀
 * （well-known 64:ff9b::/96 与 local-use 64:ff9b:1::/48；IANA 将前者标为全球可达，仍保守拒绝以防
 * 本地翻译通道）、discard-only(100::/64)、IETF 协议分配(2001::/23，含 Teredo/基准/ORCHID，其中存在
 * 全球可达子前缀，整段保守拒绝)、文档(2001:db8::/32)、6to4(2002::/16)、以及经内嵌 IPv4 解包判定的
 * IPv4-mapped/compatible。本类只负责「已覆盖段」的判定，不是对全部 IANA 特殊用途条目的穷举
 * （如 AS112/AMT 等全球可达且不形成内部路由通道的特殊用途地址不在拦截范围）。</p>
 *
 * <p>纯 JDK，无 Spring。两条门禁职责分离：</p>
 * <ul>
 *   <li>{@link #requirePublicTarget(URI)} —— 发送时权威校验，DNS 解析全部 IP 后逐一判定，
 *       覆盖字面 IP、十进制/十六进制 hostname 形态与 DNS 指向内网的 rebinding；</li>
 *   <li>{@link #requireNoForbiddenLiteral(URI)} —— 落库时门禁，仅校验字面 IP（不做 DNS），
 *       hostname 形态跳过，交发送时权威校验兜底，避免在配置写事务里阻塞 DNS。</li>
 * </ul>
 *
 * <p>旧告警/规则发送路径为什么未在解析后把连接钉住到已校验 IP：JDK {@code HttpClient} 无 逐请求 DNS 钩子，
 * 钉住需换 HTTP 栈或自造 TrustManager（后者风险更高）。这里依赖 JVM {@code InetAddress} 正缓存
 * （默认 30s）使校验后微秒级的连接复用同一解析结果；残余 {@code networkaddress.cache.ttl=0} 或
 * 攻击 DNS TTL=0 的微秒级竞态作为明确记录的剩余风险，见 DEBT D-035。公开Webhook使用support的
 * PinnedWebhookTransport复用本类地址判定并固定连接IP，不沿用该缓存假设。</p>
 */
public final class SafeOutboundHost {

    private SafeOutboundHost() {
    }

    /**
     * 是否命中保留/内网段。发送时与落库时两条门禁共用同一判定。
     *
     * @param address 待判定地址
     * @return 命中环回/内网/链路本地/组播/保留段时为 true
     */
    public static boolean isForbidden(InetAddress address) {
        byte[] embedded = embeddedIpv4OrNull(address);
        if (embedded != null) {
            return isForbiddenIpv4(embedded);
        }
        byte[] raw = address.getAddress();
        return raw.length == 4 ? isForbiddenIpv4(raw) : isForbiddenIpv6(raw);
    }

    /**
     * 发送时权威校验：仅接受完整 HTTPS 目标，且 DNS 解析后的全部 IP 均不在保留段。
     *
     * @param target 冻结的 Webhook 目标 URI
     * @throws IllegalArgumentException 非 https 方案、host 缺失、含 userInfo，或任一解析 IP 命中保留段
     * @throws UnknownHostException host 无法解析（调用方应映射为可恢复网络错误，而非非法投递）
     */
    public static void requirePublicTarget(URI target) throws UnknownHostException {
        if (!"https".equalsIgnoreCase(target.getScheme())
                || target.getHost() == null
                || target.getHost().isBlank()
                || target.getUserInfo() != null) {
            throw new IllegalArgumentException("not a plain https target");
        }
        String host = stripBrackets(target.getHost());
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isForbidden(address)) {
                throw new IllegalArgumentException("forbidden outbound host");
            }
        }
    }

    /**
     * 落库时门禁：仅校验字面 IP（IPv4 dotted-quad / IPv6），不做 DNS。hostname 及十进制/十六进制
     * 数字形态跳过，交发送时 {@link #requirePublicTarget} 权威校验。
     *
     * @param target 待落库的 Webhook 目标 URI
     * @throws IllegalArgumentException 目标是命中保留段的字面 IP
     */
    public static void requireNoForbiddenLiteral(URI target) {
        String host = stripBrackets(target.getHost());
        InetAddress literal = parseLiteralOrNull(host);
        if (literal != null && isForbidden(literal)) {
            throw new IllegalArgumentException("forbidden literal host");
        }
    }

    /** @return 命中已覆盖段的 IPv4 字节（长度 4）；以 RFC 6890 为基线，对保留/非全球可达前缀保守拒绝 */
    private static boolean isForbiddenIpv4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;
        if (a == 0 || a == 10 || a == 127) return true; // 任意本地地址、RFC1918 私有地址及环回地址
        if (a == 100 && c >= 64 && c <= 127) return true; // 运营商级地址转换网段 100.64/10（RFC 6598）
        if (a == 169 && c == 254) return true; // link-local + 云元数据
        if (a == 172 && c >= 16 && c <= 31) return true; // RFC1918 私有地址范围
        if (a == 192 && c == 0 && (d == 0 || d == 2)) return true; // IETF 协议分配 192.0.0/24 + TEST-NET-1 192.0.2/24
        if (a == 192 && c == 88 && d == 99) return true; // 废弃 6to4 中继 192.88.99/24（RFC 7526）
        if (a == 192 && c == 168) return true; // RFC1918 私有地址范围
        if (a == 198 && c >= 18 && c <= 19) return true; // 基准测试 198.18/15（RFC 2544）
        if (a == 198 && c == 51 && d == 100) return true; // 文档示例地址段 TEST-NET-2
        if (a == 203 && c == 0 && d == 113) return true; // 文档示例地址段 TEST-NET-3
        return a >= 224; // 组播 224/4 + 保留 class E 240/4 + 广播 255.255.255.255
    }

    /** @return 命中已覆盖段的 IPv6 字节（长度 16）；以 RFC 6890 为基线，对本地翻译/保留/非全球可达/内部路由通道前缀保守拒绝 */
    private static boolean isForbiddenIpv6(byte[] b) {
        if (allZero(b)) return true; // ::/128
        if (isLoopback(b)) return true; // ::1/128
        int hi = b[0] & 0xff;
        if (hi == 0xfc || hi == 0xfd) return true; // fc00::/7 唯一本地
        if (hi == 0xfe) {
            int lo2 = b[1] & 0xc0;
            if (lo2 == 0x80 || lo2 == 0xc0) return true; // fe80::/10 链路本地 / fec0::/10 废弃站本地
        }
        if (hi == 0xff) return true; // ff00::/8 组播
        if (isNat64WellKnown(b)) return true; // 64:ff9b::/96 NAT64 well-known 前缀（RFC 6052）
        if (isNat64LocalUse(b)) return true; // 64:ff9b:1::/48 NAT64 local-use 前缀（RFC 8215）
        if (isDiscardOnly(b)) return true; // 100::/64 丢弃专用地址段（RFC 6666）
        if (isIetfProtocolAssignments(b)) return true; // 2001::/23 IETF 协议分配（Teredo/基准/ORCHID）
        if (isDocumentation(b)) return true; // 2001:db8::/32 文档（RFC 3849，独立于 2001::/23）
        return isSixToFour(b); // 2002::/16 IPv6 过渡地址段（RFC 3056）
    }

    /** @return 64:ff9b::/96 —— NAT64 well-known 前缀；后 32 位嵌 IPv4，IANA 标为全球可达但保守拒绝以防本地翻译（RFC 6052） */
    private static boolean isNat64WellKnown(byte[] b) {
        if (b[0] != 0x00 || (b[1] & 0xff) != 0x64 || (b[2] & 0xff) != 0xff || (b[3] & 0xff) != 0x9b) return false;
        for (int i = 4; i < 12; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /** @return 64:ff9b:1::/48 —— NAT64 local-use 前缀，本地部署可指向内网 IPv4，保守拒绝（RFC 8215） */
    private static boolean isNat64LocalUse(byte[] b) {
        return b[0] == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && b[4] == 0x00 && b[5] == 0x01;
    }

    /** @return 100::/64 —— discard-only，仅保留不路由（RFC 6666） */
    private static boolean isDiscardOnly(byte[] b) {
        if (b[0] != 0x01 || b[1] != 0x00) return false;
        for (int i = 2; i < 8; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /** @return 2001::/23 —— IETF 协议分配；Teredo(2001::/32)、基准(2001:2::/48)、ORCHID(2001:10::/28、2001:20::/28) 均落此段，其中存在全球可达子前缀，整段保守拒绝 */
    private static boolean isIetfProtocolAssignments(byte[] b) {
        return b[0] == 0x20 && b[1] == 0x01 && (b[2] & 0xfe) == 0x00;
    }

    /** @return 2001:db8::/32 —— 文档前缀，位于 2001::/23 之外，须独立判定（RFC 3849） */
    private static boolean isDocumentation(byte[] b) {
        return b[0] == 0x20 && b[1] == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8;
    }

    /** @return 2002::/16 过渡地址段判定结果（6to4，RFC 3056） */
    private static boolean isSixToFour(byte[] b) {
        return b[0] == 0x20 && b[1] == 0x02;
    }

    /** @return 去除 IPv6 字面量的方括号后的 host */
    private static String stripBrackets(String host) {
        if (host != null && host.length() >= 2 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** @return 若 host 是普通字面 IP（IPv4 dotted-quad / IPv6）则返回其地址，否则 null（不触发 DNS） */
    private static InetAddress parseLiteralOrNull(String host) {
        if (host == null || host.isBlank()) return null;
        if (host.indexOf(':') >= 0) {
            return byNameOrNull(host); // 冒号 ⇒ IPv6 字面量尝试；reg-name 不能含冒号
        }
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null; // 仅严格 dotted-quad；数字/hex 交发送时兜底
        for (String part : host.split("\\.")) {
            if (Integer.parseInt(part) > 255) return null;
        }
        return byNameOrNull(host);
    }

    /** @return 解析失败（或需要 DNS 的 hostname 形态）返回 null */
    private static InetAddress byNameOrNull(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    /** @return 若为 IPv4-mapped（::ffff:0:0/96）或 IPv4-compatible（::/96）则返回内嵌 IPv4（末 4 字节），否则 null */
    private static byte[] embeddedIpv4OrNull(InetAddress address) {
        if (!(address instanceof Inet6Address)) return null;
        byte[] raw = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) return null;
        }
        boolean mapped = (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff;
        boolean compatible = raw[10] == 0 && raw[11] == 0;
        if (!mapped && !compatible) return null;
        byte[] v4 = new byte[4];
        System.arraycopy(raw, 12, v4, 0, 4);
        return v4;
    }

    /** @return 全部 16 字节为 0 */
    private static boolean allZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) return false;
        }
        return true;
    }

    /** @return 前 15 字节为 0 且末字节为 1（::1） */
    private static boolean isLoopback(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) return false;
        }
        return b[15] == 1;
    }
}
