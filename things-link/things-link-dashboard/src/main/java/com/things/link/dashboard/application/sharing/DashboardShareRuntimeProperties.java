package com.things.link.dashboard.application.sharing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * ADR0101匿名运行必须显式启用、绑定唯一宿主和可写日志目录。
 * @param enabled 默认关闭，不能因配置缺失静默开放匿名数据
 * @param hostOrigin 唯一HTTPS宿主Origin
 * @param allowLoopbackHttp 仅显式开发配置允许loopback HTTP
 * @param securityLogPath 必须由显式LOG_PATH提供；可写校验不代替生产挂载验收
 */
@ConfigurationProperties(prefix = "things-link.dashboard.share-runtime")
public record DashboardShareRuntimeProperties(boolean enabled, String hostOrigin,
        boolean allowLoopbackHttp, String securityLogPath) {
    /** 启用时拒绝通配、URL路径和临时猜测的默认宿主；不自动创建日志目录掩盖部署漏配。 */
    public DashboardShareRuntimeProperties {
        if (enabled) {
            if (hostOrigin == null || hostOrigin.isBlank()) throw new IllegalArgumentException("匿名分享缺少host-origin");
            URI origin = URI.create(hostOrigin);
            boolean httpLoopback = allowLoopbackHttp && "http".equals(origin.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(origin.getHost());
            if ((!"https".equals(origin.getScheme()) && !httpLoopback) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                    || origin.getPort() > 65535 || origin.getPort() == 0 || hostOrigin.contains("*")) {
                throw new IllegalArgumentException("匿名分享必须配置精确HTTPS Origin，HTTP仅显式loopback开发许可");
            }
            if (securityLogPath == null || securityLogPath.isBlank()
                    || !Files.isDirectory(Path.of(securityLogPath)) || !Files.isWritable(Path.of(securityLogPath))) {
                throw new IllegalArgumentException("匿名分享需要显式LOG_PATH指向已存在可写目录");
            }
        }
    }
}
