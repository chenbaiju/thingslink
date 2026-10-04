package com.things.link.enduser.domain;

/**
 * 终端用户在项目中的角色（ADR 0035）。
 *
 * <p>与控制台 {@code ProjectRole}（OWNER / ADMIN / OPERATOR / VIEWER）刻意分离：
 * 这是「终端用户在项目里能做什么」，不是「控制台账号在项目里能做什么」。两层权限
 * 交集 = {@code app_user_role}（项目角色）× {@code app_user_device}（设备关系角色）。
 */
public enum EndUserRole {
    /** 应用管理员：管理本项目终端用户与设备授权。 */
    APP_ADMIN,
    /** 维护者：可维护设备与绑定，不可管理其他用户。 */
    MAINTAINER,
    /** 操作员：可操作设备，不可改配置或授权。 */
    OPERATOR,
    /** 观察者：只读。 */
    OBSERVER
}
