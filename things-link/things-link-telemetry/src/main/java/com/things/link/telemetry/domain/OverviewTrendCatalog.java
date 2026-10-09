package com.things.link.telemetry.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 项目统计曲线闭集；数量为小时累计值，设备状态为小时末快照，不参与计费。 */
public final class OverviewTrendCatalog {
    /** 禁止实例化目录。 */
    private OverviewTrendCatalog() { }
    /** 图表与指标的固定显示顺序。 */
    public static final List<Chart> CHARTS = List.of(
            new Chart("messages", "设备消息数", "COUNT", false, List.of(new Metric("message.total", "总消息"), new Metric("message.report", "属性上报"), new Metric("message.get", "属性获取"), new Metric("message.set", "属性下发"), new Metric("message.cloud", "云端属性更新"), new Metric("message.event", "事件上报"), new Metric("message.command", "命令下发"), new Metric("message.reply", "命令回复"), new Metric("message.customUp", "自定义上报"), new Metric("message.customDown", "自定义下发"))),
            new Chart("active", "活跃设备数", "DEVICES", true, List.of(new Metric("device.active", "总活跃设备"))),
            new Chart("connections", "设备连接次数", "COUNT", false, List.of(new Metric("connection.mqttUp", "MQTT连接"), new Metric("connection.mqttDown", "MQTT断开"), new Metric("connection.tcpUp", "TCP连接"), new Metric("connection.tcpDown", "TCP断开"))),
            new Chart("alarms", "告警状态", "DEVICES", true, List.of(new Metric("alarm.normal", "正常"), new Metric("alarm.active", "告警"), new Metric("alarm.pending", "待定"))),
            new Chart("notifications", "告警通知次数", "COUNT", false, List.of(new Metric("notification.email", "邮件"), new Metric("notification.phone", "电话"), new Metric("notification.sms", "短信"), new Metric("notification.wechat", "微信服务号"), new Metric("notification.push", "App推送"), new Metric("notification.dingtalk", "钉钉"), new Metric("notification.wecom", "企业微信"), new Metric("notification.feishu", "飞书"), new Metric("notification.webhook", "Webhook"))),
            new Chart("rules", "规则调用量", "COUNT", false, List.of(new Metric("rule.calls", "规则调用次数"), new Metric("rule.actions", "操作执行次数"))),
            new Chart("tasks", "任务运行量", "COUNT", false, List.of(new Metric("task.runs", "任务运行次数"))),
            new Chart("automations", "自动化执行统计", "COUNT", false, List.of(new Metric("automation.completed", "完成"), new Metric("automation.failed", "失败"), new Metric("automation.skipped", "跳过"), new Metric("automation.terminated", "被终止"))),
            new Chart("scenes", "场景执行统计", "COUNT", false, List.of(new Metric("scene.completed", "完成"), new Metric("scene.failed", "失败"), new Metric("scene.skipped", "跳过"), new Metric("scene.terminated", "被终止"))),
            new Chart("mqtt", "设备MQTT流量", "BYTES", false, List.of(new Metric("bytes.mqttTotal", "MQTT总流量"), new Metric("bytes.mqttUp", "MQTT上行"), new Metric("bytes.mqttDown", "MQTT下行"))),
            new Chart("tcp", "设备TCP流量", "BYTES", false, List.of(new Metric("bytes.tcpTotal", "TCP总流量"), new Metric("bytes.tcpUp", "TCP上行"), new Metric("bytes.tcpDown", "TCP下行"))),
            new Chart("http", "设备HTTP流量", "BYTES", false, List.of(new Metric("bytes.httpTotal", "HTTP总流量"), new Metric("bytes.httpUp", "HTTP上行"), new Metric("bytes.httpDown", "HTTP下行"))));
    /** 状态指标集合，用于桶末快照聚合。 */
    public static final Set<String> GAUGES = CHARTS.stream().filter(Chart::gauge)
            .flatMap(c -> c.metrics().stream()).map(Metric::key).collect(Collectors.toUnmodifiableSet());
    /** @param key 固定指标键 @param label 中文图例 */
    public record Metric(String key, String label) { }
    /** @param key 固定图表键 @param title 标题 @param unit 单位 @param gauge 是否取桶末状态 @param metrics 图例 */
    public record Chart(String key, String title, String unit, boolean gauge, List<Metric> metrics) { }
}
