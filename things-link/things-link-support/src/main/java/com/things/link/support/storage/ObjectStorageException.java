package com.things.link.support.storage;

/** 私有对象存储技术操作失败；业务层据此保留重试或清理事实。 */
public class ObjectStorageException extends RuntimeException {

    /**
     * 创建对象存储异常。
     * @param message 不含凭据和URL的稳定描述
     * @param cause 原技术异常
     */
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
