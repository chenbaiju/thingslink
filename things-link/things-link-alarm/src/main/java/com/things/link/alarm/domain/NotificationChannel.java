package com.things.link.alarm.domain;

/** S6-2 支持的通知目标类别；渠道实现与供应商账号均不属于配置事实。 */
public enum NotificationChannel {
    /** 电子邮件目标。 */
    EMAIL,
    /** HTTP Webhook 目标；本切片不接受签名密钥。 */
    WEBHOOK,
    /** 原生 App 安装实例 PUSH；具体厂商 token 由 enduser 发送边界按稳定 ID 读取。 */
    PUSH
}
