package com.things.link.enduser.domain;

/** 当前项目接收号码；仅配置事实，不承诺通道或号码可达。 */
public record AppNotificationContact(String voiceNumber, String smsNumber, long revision) { }
