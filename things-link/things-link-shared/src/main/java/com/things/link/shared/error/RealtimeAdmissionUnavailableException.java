package com.things.link.shared.error;
/** 公开实时受理未确认；隔离Kafka工厂不得恢复为自动提交offset。 */
public final class RealtimeAdmissionUnavailableException extends RuntimeException {
    public RealtimeAdmissionUnavailableException(Throwable cause){super("公开实时持久受理未确认",cause);}
}
