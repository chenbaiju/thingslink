package com.things.link.device.application;
import java.time.Instant;
/** 可选的应用MQTT身份端口；设备域不依赖集成域，也不解析其秘密。 */
public interface ApplicationMqttAccess {
    String ZONE="tc_application";
    static boolean applicationIdentity(String username){return username!=null&&username.startsWith("tc-app-v1:");}
    Authentication authenticate(String username,String password,String clientId,String peerIp,String zone);
    boolean authorize(String username,String clientId,String peerIp,String zone,String topic,String action,String qos);
    record Authentication(boolean allowed,Instant expiresAt){
        public static Authentication deny(){return new Authentication(false,null);}
    }
}
