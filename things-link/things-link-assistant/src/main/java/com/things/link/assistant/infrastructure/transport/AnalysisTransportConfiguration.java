package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.AnalysisTransport;
import com.things.link.assistant.application.AnalysisReleaseReview;
import com.things.link.assistant.application.PreparedModelEvidence;
import com.things.link.assistant.domain.AnalysisCall;
import java.util.Arrays;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认关闭的固定内部网络装配；完整配置也不授予模型准入或探针发送许可。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisTransportConfiguration.Properties.class)
public class AnalysisTransportConfiguration {
    /** 服务级配置，不接受浏览器参数；证书密码不是项目模型密钥，字符串表示隐藏内容。 */
    @ConfigurationProperties(value = "things-link.assistant.analysis.transport", ignoreUnknownFields = false)
    public static class Properties {
        private URI origin;
        private Path identityFile, trustFile;
        private String identityPassword, trustPassword, counterSha256, executionCandidateSha256;
        public URI getOrigin() { return origin; }
        public void setOrigin(URI value) { origin = value; }
        public Path getIdentityFile() { return identityFile; }
        public void setIdentityFile(Path value) { identityFile = value; }
        public Path getTrustFile() { return trustFile; }
        public void setTrustFile(Path value) { trustFile = value; }
        public String getIdentityPassword() { return identityPassword; }
        public void setIdentityPassword(String value) { identityPassword = value; }
        public String getTrustPassword() { return trustPassword; }
        public void setTrustPassword(String value) { trustPassword = value; }
        public String getCounterSha256() { return counterSha256; }
        public void setCounterSha256(String value) { counterSha256 = value; }
        public String getExecutionCandidateSha256() { return executionCandidateSha256; }
        public void setExecutionCandidateSha256(String value) { executionCandidateSha256 = value; }
        @Override public String toString() { return "内部分析配置[内容已隐藏]"; }
    }

    /**
     * 全空时禁用，完整配置只装配固定内部通道；缺项或无效内容拒绝启动。
     * @param properties 服务端专用配置，装配后不再逐次读取其可变属性
     * @return 已冻结的内部端口，不包含业务准入或项目凭据缓存
     */
    @Bean public AnalysisTransport analysisTransport(Properties properties) {
        if (properties.origin == null && properties.identityFile == null && properties.trustFile == null
                && properties.identityPassword == null && properties.trustPassword == null && properties.counterSha256 == null && properties.executionCandidateSha256 == null)
            return disabled();
        char[] identityPassword = null, trustPassword = null;
        SingleInternalAnalysisClient template = null;
        String counter = properties.counterSha256;
        String candidate = properties.executionCandidateSha256;
        try {
            if (properties.origin == null || properties.identityFile == null || properties.trustFile == null
                    || properties.identityPassword == null || properties.trustPassword == null
                    || counter == null || !counter.matches("[0-9a-f]{64}")
                    || candidate == null || !candidate.matches("[0-9a-f]{64}")) throw new IllegalStateException();
            identityPassword = properties.identityPassword.toCharArray();
            trustPassword = properties.trustPassword.toCharArray();
            template = new SingleInternalAnalysisClient(properties.origin, properties.identityFile, identityPassword,
                    properties.trustFile, trustPassword);
        } catch (RuntimeException ignored) {
            // 不把文件路径、证书密码或底层异常放进装配错误链。
        } finally {
            if (identityPassword != null) Arrays.fill(identityPassword, '\0');
            if (trustPassword != null) Arrays.fill(trustPassword, '\0');
        }
        if (template == null) throw new IllegalStateException("INVALID_ANALYSIS_TRANSPORT");
        var fixed = template;
        return new AnalysisTransport() {
            @Override public boolean ready() { return true; }
            @Override public java.util.Optional<AnalysisReleaseReview.Approval> review() {
                return AnalysisReleaseReview.forTransport(candidate, counter);
            }
            @Override public Receipt execute(AnalysisCall call, PreparedModelEvidence.Input input, byte[] credential) {
                try {
                    var approval = review();
                    if (approval.isPresent())
                        return fixed.newAttempt().executeReviewed(call, input, counter, candidate, credential, approval.get());
                    fixed.newAttempt().execute(call, input, counter, candidate, credential);
                    return Receipt.UNQUALIFIED;
                } finally {
                    if (credential != null) Arrays.fill(credential, (byte) 0);
                }
            }
            @Override public String toString() { return "内部分析通道[未准入]"; }
        };
    }

    /** @return 无网络能力的禁用端口，误调用时仍清除凭据 */
    private static AnalysisTransport disabled() {
        return new AnalysisTransport() {
            @Override public boolean ready() { return false; }
            @Override public Receipt execute(AnalysisCall call, PreparedModelEvidence.Input input, byte[] credential) {
                if (credential != null) Arrays.fill(credential, (byte) 0);
                throw new IllegalStateException("ANALYSIS_TRANSPORT_DISABLED");
            }
        };
    }
}
