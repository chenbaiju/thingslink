package com.things.link.rule.application.outbox;

/** 自动化动作永久拒绝；不携带原始输入或供应商异常，绝不按瞬时故障重试。 */
public final class AutomationActionRejectedException extends RuntimeException {
    public AutomationActionRejectedException() { super("ACTION_REJECTED"); }
}
