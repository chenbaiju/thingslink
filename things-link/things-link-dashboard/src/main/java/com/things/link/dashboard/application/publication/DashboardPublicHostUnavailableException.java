package com.things.link.dashboard.application.publication;

/** 公开静态资格缺失/繁忙；仅固定分类，不带路径、秘密或内部异常正文。 */
public final class DashboardPublicHostUnavailableException extends RuntimeException {
    /** 创建没有内部数据的固定不可用结果。 */
    public DashboardPublicHostUnavailableException() { super("PUBLIC_HOST_UNAVAILABLE"); }
}
