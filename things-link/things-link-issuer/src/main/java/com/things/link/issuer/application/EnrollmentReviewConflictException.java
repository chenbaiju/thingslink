package com.things.link.issuer.application;

/** 同案审核材料或重试内容不一致；不得覆盖既有只追加事实。 */
public final class EnrollmentReviewConflictException extends RuntimeException {
    public EnrollmentReviewConflictException() {
        super("自部署申请核验事实冲突");
    }
}
