package com.things.link.enduser.domain;

/** 账号级推送偏好，缺行沿既有开启语义且版本为零。 */
public record AppNotificationPreference(boolean appPushEnabled, long revision) {
}
