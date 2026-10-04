package com.things.link.telemetry.application;

/** ADR0070：仅表示已定位命令的派发信封或原不可变事实无效，ingestion可明确送入无效消息路径。 */
public class InvalidCommandDispatchException extends RuntimeException {
    /** @param message 不含输入报文或设备凭据的稳定拒绝说明 */
    public InvalidCommandDispatchException(String message) { super(message); }
    /** @param message 稳定拒绝说明 @param cause 仅JSON解析等确定信封错误，不能包装SQL或事务异常 */
    public InvalidCommandDispatchException(String message, Throwable cause) { super(message, cause); }
}
