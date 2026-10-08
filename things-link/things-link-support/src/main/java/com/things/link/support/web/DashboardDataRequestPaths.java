package com.things.link.support.web;

import java.util.Set;
import java.util.regex.Pattern;

/** ADR0099的数据读取路由登记；只分类精确方法和模板，不授予认证、项目或设备访问权。 */
public final class DashboardDataRequestPaths {
    /** 三个App POST是封闭查询，不能被一般POST写入策略阻断归档读取。 */
    private static final Set<String> APP_POST = Set.of(
            "/api/v1/app/devices/snapshots/query", "/api/v1/app/devices/current-values/query",
            "/api/v1/app/alarms/query");
    /** Console使用独立路径名，旧current-values批量接口继续保留既有合同。 */
    private static final Pattern CONSOLE_POST = Pattern.compile(
            "^/api/v1/projects/[^/]+/(?:devices/(?:snapshots|current-value-snapshots)/query|alarms/query)$");
    /** 个人集合报告仅汇编历史来源，精确登记读取配额与认证前禁止缓存。 */
    private static final Pattern PERSONAL_FACT_COLLECTION_POST = Pattern.compile(
            "^/api/v1/projects/[^/]+/assistant/fact-reports/collection$");
    /** 两个App GET也必须在认证和参数失败前写入no-store。 */
    private static final Pattern APP_GET = Pattern.compile(
            "^/api/v1/app/devices/(?:catalog|[^/]+/properties/[^/]+/history/versioned)$");

    /** Console批量设备告警GET只增加缓存控制，不改变写配额或认证策略。 */
    private static final Pattern CONSOLE_ALARM_STATUS_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/alarms/device-status$");

    /** 设备命令历史是精确GET，只扩展缓存控制，不改变命令提交或单条结果权限。 */
    private static final Pattern CONSOLE_COMMAND_HISTORY_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/commands$");

    /** 事件列表及单条事实在认证、归属和参数失败前同样禁止缓存，不扩展未来写入口。 */
    private static final Pattern CONSOLE_DEVICE_EVENTS_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/events(?:/[^/]+)?$");

    /** 当前设备授权目录只登记缓存控制，成员权限仍由应用层核验。 */
    private static final Pattern CONSOLE_DEVICE_END_USERS_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/end-users$");

    /** 任务定义与目标历史只读入口，其他任务写入不继承缓存分类。 */
    private static final Pattern CONSOLE_DEVICE_TASKS_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/(?:task-jobs|task-executions)$");

    /** 自动化配置与执行历史精确读取，权限仍由各用途自行核验。 */
    private static final Pattern CONSOLE_DEVICE_AUTOMATIONS_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/(?:automations|automation-executions)$");

    private static final Pattern CONSOLE_DEVICE_SCENES_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/(?:scene-candidates|scene-executions)$");

    private static final Pattern CONSOLE_DEVICE_MESSAGE_RULES_GET = Pattern.compile(
            "^/api/v1/projects/[^/]+/devices/[^/]+/message-rule-(?:candidates|executions|actions)$");

    /** 纯静态路由目录，不创建含可变注册状态的实例。 */
    private DashboardDataRequestPaths() { }

    /** App生命周期只读例外仅对三个完整POST路径生效。 */
    public static boolean isAppPostRead(String method, String path) {
        return "POST".equals(method) && APP_POST.contains(path);
    }

    /** 精确只读POST不扣写额度；读取短窗、项目和租户额度继续保留，分类不授予权限。 */
    public static boolean isConsolePostRead(String method, String path) {
        return "POST".equals(method) && path != null && (CONSOLE_POST.matcher(path).matches()
                || PERSONAL_FACT_COLLECTION_POST.matcher(path).matches());
    }

    /** 新数据合同的完整读取入口；相邻路径、其他方法及未来匿名分享均不自动加入。 */
    public static boolean isDataRead(String method, String path) {
        return isAppPostRead(method, path) || isConsolePostRead(method, path)
                || ("GET".equals(method) && path != null && (APP_GET.matcher(path).matches()
                    || CONSOLE_ALARM_STATUS_GET.matcher(path).matches()
                    || CONSOLE_COMMAND_HISTORY_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_EVENTS_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_END_USERS_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_TASKS_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_AUTOMATIONS_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_SCENES_GET.matcher(path).matches()
                    || CONSOLE_DEVICE_MESSAGE_RULES_GET.matcher(path).matches()));
    }
}
