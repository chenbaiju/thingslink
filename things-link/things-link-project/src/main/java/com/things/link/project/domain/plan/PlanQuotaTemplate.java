package com.things.link.project.domain.plan;

/**
 * 一个产品修订版的冻结配额数值：S14-0 冻结的 16 个数值维度在 {@code sys_quota_policy} 上的具体取值。
 *
 * <p>它是「目录冻结值 → 运行时策略模板」的唯一映射结果，也是播种后的漂移校验基准。
 * 每个字段都必须有确定值：{@code 0} 只允许出现在冻结正文明确写 0 的去重协作者席位，
 * 其它维度用 {@code 0} 表示未知会被拒绝（与数据库 {@code sys_quota_policy_plan_template_ck} 一致）。
 *
 * <p>对象存储额度在这里换算成字节（1024 进制，与 S7 FREE 模板 1 GiB = 1073741824 一致），
 * 因为运行时列 {@code storage_bytes_limit} 的语义是「当前字节数上限」，不再保留 MB/GB 文本。
 *
 * <p>本类型只承载数值，不承载档位身份：档位编码是 {@code Map} 的键，修订版标识由播种方按
 * {@code revision} 参数传入。这样同一份冻结数值可以被多个只调价不改额度的修订版复用。
 *
 * @param projectsMax 冻结维度 {@code PROJECTS_MAX}
 * @param devicesMax 冻结维度 {@code DEVICES_MAX}
 * @param endUsersMax 冻结维度 {@code END_USERS_MAX}
 * @param dashboardsMax 冻结维度 {@code DASHBOARDS_MAX}
 * @param externalCollaboratorSeats 冻结维度 {@code EXTERNAL_COLLABORATOR_SEATS}，允许为 0
 * @param historyWindowUnit 冻结维度 {@code HISTORY_WINDOW} 的单位，{@code DAY} 或 {@code MONTH}
 * @param historyWindowAmount 冻结维度 {@code HISTORY_WINDOW} 的数量
 * @param uplinkMessageDailyLimit 冻结维度 {@code UPLINK_MESSAGE_DAILY}
 * @param downlinkMessageDailyLimit 冻结维度 {@code DOWNLINK_MESSAGE_DAILY}
 * @param restApiWriteDailyLimit 冻结维度 {@code REST_API_WRITE_DAILY}
 * @param restApiRatePerMinute 冻结维度 {@code REST_API_RATE_PER_MINUTE}
 * @param websocketConnectionLimit 冻结维度 {@code WEBSOCKET_CONNECTION_CONCURRENT}
 * @param scriptRuleConcurrency 冻结维度 {@code SCRIPT_RULE_CONCURRENCY}
 * @param scriptRuleExecutionDailyLimit 冻结维度 {@code SCRIPT_RULE_EXECUTION_DAILY}
 * @param scriptRuleCpuMillisDailyLimit 冻结维度 {@code SCRIPT_RULE_CPU_MILLIS_DAILY}
 * @param notificationDeliveryDailyLimit 冻结维度 {@code NOTIFICATION_DELIVERY_DAILY}
 * @param storageBytesLimit 冻结维度 {@code STORAGE_LIMIT} 换算后的字节数
 */
public record PlanQuotaTemplate(
        long projectsMax,
        long devicesMax,
        long endUsersMax,
        long dashboardsMax,
        long externalCollaboratorSeats,
        String historyWindowUnit,
        long historyWindowAmount,
        long uplinkMessageDailyLimit,
        long downlinkMessageDailyLimit,
        long restApiWriteDailyLimit,
        long restApiRatePerMinute,
        long websocketConnectionLimit,
        long scriptRuleConcurrency,
        long scriptRuleExecutionDailyLimit,
        long scriptRuleCpuMillisDailyLimit,
        long notificationDeliveryDailyLimit,
        long storageBytesLimit) {

    /** 冻结模板必须是完整确定值，禁止把未知项带进运行时策略。 */
    public PlanQuotaTemplate {
        if (!"DAY".equals(historyWindowUnit) && !"MONTH".equals(historyWindowUnit)) {
            throw new IllegalArgumentException("历史窗口单位只允许 DAY 或 MONTH");
        }
        requirePositive(projectsMax, "项目数上限");
        requirePositive(devicesMax, "设备数上限");
        requirePositive(endUsersMax, "终端用户数上限");
        requirePositive(dashboardsMax, "看板数上限");
        if (externalCollaboratorSeats < 0) {
            throw new IllegalArgumentException("协作者席位不得为负数");
        }
        requirePositive(historyWindowAmount, "历史窗口数量");
        requirePositive(uplinkMessageDailyLimit, "上行日额度");
        requirePositive(downlinkMessageDailyLimit, "下行日额度");
        requirePositive(restApiWriteDailyLimit, "REST 写请求日额度");
        requirePositive(restApiRatePerMinute, "REST 每分钟速率");
        requirePositive(websocketConnectionLimit, "WebSocket 并发连接");
        requirePositive(scriptRuleConcurrency, "脚本规则并发");
        requirePositive(scriptRuleExecutionDailyLimit, "脚本规则执行日额度");
        requirePositive(scriptRuleCpuMillisDailyLimit, "脚本规则 CPU 日额度");
        requirePositive(notificationDeliveryDailyLimit, "通知投递日额度");
        requirePositive(storageBytesLimit, "对象存储字节上限");
    }

    /**
     * 冻结维度一律不得用 0 表示未知。
     *
     * @param value 待校验数值
     * @param name 维度名称，用于失败信息
     */
    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须为正数，0 不能表示未知");
        }
    }
}
