package com.things.link.device.application.schema;

/** 物模型 Schema 无法解析或不符合平台当前支持的 JSON Schema 子集。 */
public class InvalidThingModelSchemaException extends RuntimeException {
    /** @param message 仅供服务端诊断的失败原因 @param cause 原始解析异常 */
    public InvalidThingModelSchemaException(String message, Throwable cause) { super(message, cause); }

    /** @param message 仅供服务端诊断的失败原因 */
    public InvalidThingModelSchemaException(String message) { super(message); }
}
