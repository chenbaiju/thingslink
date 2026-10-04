package com.things.link.support.storage;

/** 对象上传因尝试截止或外部取消而主动终止。 */
public final class ObjectUploadAbortedException extends ObjectStorageException {

    /** 是否由单调截止触发。 */
    private final boolean timedOut;

    /**
     * @param message 不含客户数据的诊断
     * @param timedOut true表示整个尝试已达到时间上限
     */
    public ObjectUploadAbortedException(String message, boolean timedOut) {
        super(message, new IllegalStateException(message));
        this.timedOut = timedOut;
    }

    /** @return 是否由整个尝试时间上限触发 */
    public boolean timedOut() {
        return timedOut;
    }
}
