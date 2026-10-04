package com.things.link.integration.application;
import java.util.*;
public interface RealtimeMqttSessions {
    boolean configured();
    List<Session> active();
    boolean disconnect(UUID ticket);
    void cancelActive();
    record Session(UUID ticket,String peerIp){}
}
