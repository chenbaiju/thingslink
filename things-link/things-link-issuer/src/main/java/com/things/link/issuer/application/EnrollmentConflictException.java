package com.things.link.issuer.application;

/** 同一申请或部署身份已登记不同封套，不能覆盖原待审事实。 */
public final class EnrollmentConflictException extends IllegalArgumentException {
    public EnrollmentConflictException() {
        super("申请 ID 或部署身份已登记其他内容");
    }
}
