package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ProbeLedger.Attempt;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;

/** 内部探针传输装配；全部未配置时禁用，部分配置时拒绝启动，禁止隐式降级。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ProbeTransportConfiguration.Properties.class)
public class ProbeTransportConfiguration {
    /** 服务级地址与证书库配置，不承载项目模型密钥；字符串输出统一脱敏。 */
    @ConfigurationProperties("things-link.assistant.probe.transport")
    public static class Properties {
        private URI origin;
        private Path identityFile,trustFile;
        private String identityPassword,trustPassword;
        public URI getOrigin(){return origin;} public void setOrigin(URI v){origin=v;}
        public Path getIdentityFile(){return identityFile;} public void setIdentityFile(Path v){identityFile=v;}
        public Path getTrustFile(){return trustFile;} public void setTrustFile(Path v){trustFile=v;}
        public String getIdentityPassword(){return identityPassword;} public void setIdentityPassword(String v){identityPassword=v;}
        public String getTrustPassword(){return trustPassword;} public void setTrustPassword(String v){trustPassword=v;}
        @Override public String toString(){return "ProbeTransportProperties[REDACTED]";}
    }
    /**
     * 按完整配置创建固定目标客户端，不发起探针或付费调用。
     * @param p 服务级传输配置，全部为空时返回禁用端口
     * @return 已装配的双向 TLS 客户端或明确禁用的端口
     * @throws IllegalStateException 配置不完整或固定地址、证书不满足要求
     */
    @Bean ProbeTransport probeTransport(Properties p){
        if(p.origin==null&&p.identityFile==null&&p.trustFile==null&&p.identityPassword==null&&p.trustPassword==null)
            return new ProbeTransport(){public boolean ready(){return false;}public ProbeResult execute(Attempt a,ProbeAuthorization g,byte[] c){throw new IllegalStateException("PROBE_TRANSPORT_DISABLED");}};
        if(p.origin==null||p.identityFile==null||p.trustFile==null||p.identityPassword==null||p.trustPassword==null)
            throw new IllegalStateException("INVALID_PROBE_TRANSPORT");
        return new InternalProbeClient(p.origin,p.identityFile,p.identityPassword.toCharArray(),p.trustFile,p.trustPassword.toCharArray());
    }
}
