package com.things.link.project.domain.plan;

/**
 * 四档冻结模板的**运行时保护与安全默认值**（S14-6f）。
 *
 * <p>这些列是 S7 的过载保护旋钮与安全日额度，**不是可售档位额度**：S7 时代所有租户（含 FREE）
 * 都用同一组值，档位差异只体现在目录冻结维度上。因此四档共用同一组默认值，恢复 S14 之前的保护水平。
 *
 * <p><b>为什么必须显式写入而不是留 NULL</b>：运行时把 NULL 解释为**不限**
 * （见 {@code DeviceUplinkRateLimiter} 的「null 字段代表不限，0 代表本维度禁止新消息」），
 * 而 S14-2a 起新租户一律绑定 {@code PLAN_R1_FREE}。留 NULL 等于给所有新租户关掉上行限流、
 * 任务派发与规则队列容量、字节与点位日额度——既违背 S14 冻结的「禁止 NULL=不限」，也把 S7 的保护弄丢。
 * 数据库侧由 {@code V20260917_0120} 只补空回填，并以迁移守卫与
 * {@code sys_quota_policy_plan_template_runtime_protection_ck} 把「模板行不得 NULL/0」变成数据库不变量；
 * 本类保证应用侧播种路径（新建模板行）写下同样的值。
 *
 * <p>唯一不在此列的运行时旋钮是 {@code rest_api_read_rate_per_minute}：它取该档**已冻结**的
 * {@code rest_api_rate_per_minute}（产品速率维度 {@code REST_API_RATE_PER_MINUTE}），因此随档位变化，
 * 不是与档位无关的平面默认值；{@code rest_api_write_rate_per_minute} 则在 S14-1b 已按同一冻结值播种。
 */
public final class PlanRuntimeDefaults {

    /** S7 每设备上行令牌补充速率（个/秒）。 */
    public static final long UPLINK_DEVICE_REFILL_PER_SECOND = 10L;

    /** S7 每设备上行令牌桶容量。 */
    public static final long UPLINK_DEVICE_BURST_CAPACITY = 20L;

    /** S7 租户级上行每秒上限。 */
    public static final long UPLINK_TENANT_PER_SECOND_LIMIT = 1_000L;

    /** S7 租户级上行每分钟上限。 */
    public static final long UPLINK_TENANT_PER_MINUTE_LIMIT = 60_000L;

    /** S7 REST 读每秒上限。 */
    public static final long REST_API_READ_RATE_PER_SECOND = 20L;

    /** S7 REST 写每秒上限。 */
    public static final long REST_API_WRITE_RATE_PER_SECOND = 10L;

    /** S7 任务派发每秒上限（项目维度）。 */
    public static final long TASK_PROJECT_DISPATCH_PER_SECOND = 20L;

    /** S7 任务派发每秒上限（租户维度）。 */
    public static final long TASK_TENANT_DISPATCH_PER_SECOND = 100L;

    /** S7 规则租户队列容量。 */
    public static final long RULE_TENANT_QUEUE_CAPACITY = 100L;

    /** S7 规则项目队列容量。 */
    public static final long RULE_PROJECT_QUEUE_CAPACITY = 20L;

    /** 上行字节安全日额度（1 GiB，S7 默认；无目录维度，故按默认值回填）。 */
    public static final long UPLINK_BYTES_DAILY_LIMIT = 1_073_741_824L;

    /** 时序点安全日额度（S7 默认；无目录维度，故按默认值回填）。 */
    public static final long TIME_SERIES_POINT_DAILY_LIMIT = 1_000_000L;

    /** 工具类不可实例化。 */
    private PlanRuntimeDefaults() {
    }
}
