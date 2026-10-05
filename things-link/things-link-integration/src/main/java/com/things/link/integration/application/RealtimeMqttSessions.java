package com.things.link.integration.application;
import java.util.*;
/** 可信实时 MQTT 会话管理端口，仅用于配置检查、会话枚举和连接清理。 */
public interface RealtimeMqttSessions {
    boolean configured();
    List<Session> active();
    boolean disconnect(UUID ticket);
    void cancelActive();
    record Session(UUID ticket,String peerIp){}
}
