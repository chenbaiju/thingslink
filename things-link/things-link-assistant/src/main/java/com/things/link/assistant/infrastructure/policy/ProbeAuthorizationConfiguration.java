package com.things.link.assistant.infrastructure.policy;

import com.things.link.assistant.application.ProbeAuthorization;
import com.things.link.assistant.application.ProbeAuthorizationProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 从服务端启动配置冻结单批探针授权；缺省禁用，不接受浏览器创建新授权。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ProbeAuthorizationConfiguration.Properties.class)
public class ProbeAuthorizationConfiguration {
    @ConfigurationProperties("things-link.assistant.probe")
    public static class Properties {
        private String authorizationId,environmentId,manifestSha256;
        private UUID projectId;
        private long configurationRevision;
        public String getAuthorizationId() { return authorizationId; }
        public void setAuthorizationId(String v) { authorizationId=v; }
        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String v) { environmentId=v; }
        public String getManifestSha256() { return manifestSha256; }
        public void setManifestSha256(String v) { manifestSha256=v; }
        public UUID getProjectId() { return projectId; }
        public void setProjectId(UUID v) { projectId=v; }
        public long getConfigurationRevision() { return configurationRevision; }
        public void setConfigurationRevision(long v) { configurationRevision=v; }
        @Override public String toString() { return "ProbeAuthorizationProperties[REDACTED]"; }
    }
    @Bean ProbeAuthorizationProvider probeAuthorizationProvider(Properties p) {
        if (p.authorizationId==null && p.environmentId==null && p.projectId==null
                && p.manifestSha256==null && p.configurationRevision==0) return id -> Optional.empty();
        var frozen=new ProbeAuthorization(p.authorizationId,p.environmentId,p.projectId,p.configurationRevision,p.manifestSha256);
        return new ProbeAuthorizationProvider() {
            @Override public Optional<ProbeAuthorization> find(String id) { return frozen.id().equals(id) ? Optional.of(frozen) : Optional.empty(); }
            @Override public Optional<ProbeAuthorization> current() { return Optional.of(frozen); }
        };
    }
}
