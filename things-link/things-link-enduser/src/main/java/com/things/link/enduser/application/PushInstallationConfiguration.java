package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/** 安装关联白名单；缺配置拒绝，MOCK也必须显式配置且不得作为真实通道证据。 */
@ConfigurationProperties("things-link.app-push-installations")
public record PushInstallationConfiguration(String digestKey, Map<String, Channel> channels) {
    /** 配置日志不得泄露摘要私钥。 */
    @Override public String toString() { return "PushInstallationConfiguration[digestKey=***]"; }

    /** @param provider 厂商 @param applicationId 包名或Bundle ID @param environment 通道环境 @param enabled 是否启用 */
    public record Channel(AppPushToken.Provider provider, String applicationId, String environment, boolean enabled) {
        /** 通道身份不可因同名配置变更而悄悄将旧token投向其他应用或环境。 */
        public String fingerprint() {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((provider+"\0"+applicationId+"\0"+environment).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("缺少SHA-256",e); }
        }
    }
    /** @param id 非敏感白名单ID @return 当前可用配置；缺失、不完整或关闭时503 */
    public Channel requireChannel(String id) {
        Channel c=channels==null?null:channels.get(id);
        if(c==null||!c.enabled()||c.provider()==null||c.applicationId()==null||c.applicationId().isBlank()
                ||c.environment()==null||c.environment().isBlank())throw new BusinessException(EndUserErrorCode.PUSH_INSTALLATION_UNAVAILABLE);
        try { if(java.util.Base64.getDecoder().decode(digestKey==null?"":digestKey).length!=32)
            throw new BusinessException(EndUserErrorCode.PUSH_INSTALLATION_UNAVAILABLE); }
        catch(IllegalArgumentException e) { throw new BusinessException(EndUserErrorCode.PUSH_INSTALLATION_UNAVAILABLE); }
        return c;
    }
}
