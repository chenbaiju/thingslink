package com.things.link.ingestion.infrastructure.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * ADR0099两种宿主的精确来源配置；空列表关闭对应握手，不能隐式允许同源或loopback。
 * @param appAllowedOrigins WebApp显式来源
 * @param consoleAllowedOrigins Console显式来源
 */
@ConfigurationProperties(prefix = "things-link.ingestion.dashboard-realtime")
public record DashboardWebSocketProperties(List<String> appAllowedOrigins, List<String> consoleAllowedOrigins) {
    /** 启动时排除通配、路径和凭据，保留协议、主机和端口的精确字面匹配。 */
    public DashboardWebSocketProperties {
        appAllowedOrigins = validate(appAllowedOrigins);
        consoleAllowedOrigins = validate(consoleAllowedOrigins);
    }

    /** 配置必须是浏览器Origin格式，不接受URL前缀匹配。 */
    private static List<String> validate(List<String> values) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            URI uri = URI.create(value);
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || value.contains("*")) {
                throw new IllegalArgumentException("看板实时来源必须是显式HTTP(S) Origin");
            }
        }
        return List.copyOf(values);
    }
}
