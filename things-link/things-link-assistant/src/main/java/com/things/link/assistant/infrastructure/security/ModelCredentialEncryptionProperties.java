package com.things.link.assistant.infrastructure.security;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** 独立服务器秘密配置；无缺省密钥，不打印属性值。 */
@ConfigurationProperties("things-link.assistant.credentials")
public class ModelCredentialEncryptionProperties {
    private String activeKeyId;
    private Map<String,String> keys = Map.of();
    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String value) { activeKeyId = value; }
    public Map<String,String> getKeys() { return keys; }
    public void setKeys(Map<String,String> value) { keys = value; }
    @Override public String toString() { return "ModelCredentialEncryptionProperties[REDACTED]"; }
}
