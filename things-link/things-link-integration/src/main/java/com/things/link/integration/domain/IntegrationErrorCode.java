package com.things.link.integration.domain;
import com.things.link.shared.error.ErrorCode;
/** ADR0170：独立集成段，登记先于启用；不复用成员管理错误语义。 */
public enum IntegrationErrorCode implements ErrorCode {
    MANAGE_FORBIDDEN(80001,"当前角色无权管理公开集成",403),
    NOT_ENABLED(80002,"公开集成尚未启用",503),
    ACCESS_INVALID(80003,"公开集成凭据无效",401),
    ACCESS_FORBIDDEN(80004,"公开集成权限不足",403),
    REALTIME_CAPACITY(80005,"公开实时订阅额度不足",429),
    REALTIME_TICKET_INVALID(80006,"公开实时票据无效",401),
    REALTIME_UNAVAILABLE(80007,"公开实时授权或额度暂不可用",503);
    private final int code,status;
    private final String message;
    IntegrationErrorCode(int code,String message,int status){this.code=code;this.message=message;this.status=status;}
    @Override public int code(){return code;}
    @Override public String defaultMessage(){return message;}
    @Override public int httpStatus(){return status;}
}
