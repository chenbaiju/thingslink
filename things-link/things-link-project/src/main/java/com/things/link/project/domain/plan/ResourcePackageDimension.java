package com.things.link.project.domain.plan;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 可被资源包扩容的冻结维度登记表（S14-4a，S14-0 P6）。
 *
 * <p>它是「包维度 ↔ 基础档维度」的唯一匹配规则：维度编码必须相同，且单位与计量窗口也必须
 * 相同（{@link #matches(ResourcePackageAddition, PlanQuotaTemplate)}）。不同单位的加数直接
 * 不参与合成，而不是被硬凑成同一个数字。
 *
 * <p>{@code HISTORY_WINDOW} 的单位随档位冻结值变化（DAY 或 MONTH），因此它的期望单位取自
 * 基础模板本身；这是本登记表里唯一不能写死单位的维度。
 *
 * <h2>平台安全硬上限</h2>
 * S14-0 P6 冻结「上界为平台安全硬上限」。当前冻结记录里**没有**为这 16 个目录维度定义
 * 平台硬上限：P1-3 的「单账号参与外部项目上限 50」是账号维度的风控上限（不是本表的
 * {@code EXTERNAL_COLLABORATOR_SEATS} 租户去重席位），P3 的「单对象大小」是对象维度常量，
 * 两者都不在这里的维度轴上，套用会把风控常量误当可售权益封顶。因此 {@link #platformHardLimit()}
 * 今天对全部维度返回空；封顶机制保留，冻结新增硬上限时只需在对应枚举项填上数值。
 */
public enum ResourcePackageDimension {

    /** 自有项目数上限（{@code COUNT / NONE}）。 */
    PROJECTS_MAX("COUNT", "NONE"),
    /** 设备数上限（{@code COUNT / NONE}）。 */
    DEVICES_MAX("COUNT", "NONE"),
    /** 终端用户数上限（{@code COUNT / NONE}）。 */
    END_USERS_MAX("COUNT", "NONE"),
    /** 看板数上限（{@code COUNT / NONE}）。 */
    DASHBOARDS_MAX("COUNT", "NONE"),
    /** 去重外部协作者席位（{@code COUNT / NONE}）。 */
    EXTERNAL_COLLABORATOR_SEATS("COUNT", "NONE"),
    /** 历史查询窗口；单位取自基础档冻结值（DAY 或 MONTH）。 */
    HISTORY_WINDOW(null, "ROLLING"),
    /** 上行消息 UTC 日额度。 */
    UPLINK_MESSAGE_DAILY("MESSAGE", "UTC_DAY"),
    /** 下行消息 UTC 日额度。 */
    DOWNLINK_MESSAGE_DAILY("MESSAGE", "UTC_DAY"),
    /** REST 写请求 UTC 日额度。 */
    REST_API_WRITE_DAILY("REQUEST", "UTC_DAY"),
    /** REST 每分钟请求速率。 */
    REST_API_RATE_PER_MINUTE("REQUEST", "MINUTE"),
    /** WebSocket 并发连接。 */
    WEBSOCKET_CONNECTION_CONCURRENT("CONNECTION", "CONCURRENT"),
    /** 脚本/规则并发执行。 */
    SCRIPT_RULE_CONCURRENCY("EXECUTION", "CONCURRENT"),
    /** 脚本/规则 UTC 日执行次数。 */
    SCRIPT_RULE_EXECUTION_DAILY("EXECUTION", "UTC_DAY"),
    /** 脚本/规则 UTC 日 CPU 毫秒。 */
    SCRIPT_RULE_CPU_MILLIS_DAILY("MILLISECOND", "UTC_DAY"),
    /** 通知投递 UTC 日尝试数。 */
    NOTIFICATION_DELIVERY_DAILY("DELIVERY", "UTC_DAY"),
    /** 对象存储字节上限；包额度以字节计。 */
    STORAGE_LIMIT("BYTE", "NONE");

    /** 期望单位；{@code null} 表示取自基础档冻结值（仅 {@link #HISTORY_WINDOW}）。 */
    private final String expectedUnit;
    /** 期望计量窗口。 */
    private final String expectedWindow;

    ResourcePackageDimension(String expectedUnit, String expectedWindow) {
        this.expectedUnit = expectedUnit;
        this.expectedWindow = expectedWindow;
    }

    /**
     * 按维度编码查找登记项。
     *
     * @param dimensionCode 冻结维度编码
     * @return 登记的维度；未知编码返回空（合成时按「不参与」处理）
     */
    public static Optional<ResourcePackageDimension> fromCode(String dimensionCode) {
        if (dimensionCode == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(dimension -> dimension.name().equals(dimensionCode))
                .findFirst();
    }

    /**
     * 判断一个资源包加数是否与该基础模板的同名维度匹配。
     *
     * @param addition 仓库读取的加数
     * @param base 基础档冻结模板
     * @return 维度同名且单位、窗口都一致时返回 {@code true}
     */
    public boolean matches(ResourcePackageAddition addition, PlanQuotaTemplate base) {
        return name().equals(addition.dimensionCode())
                && unit(base).equals(addition.unit())
                && expectedWindow.equals(addition.window());
    }

    /**
     * 该维度对基础模板的期望单位。
     *
     * @param base 基础档冻结模板
     * @return 固定单位；{@link #HISTORY_WINDOW} 返回基础模板的历史窗口单位
     */
    public String unit(PlanQuotaTemplate base) {
        return expectedUnit != null ? expectedUnit : base.historyWindowUnit();
    }

    /**
     * 读取该维度在冻结模板里的数值（S14-4c 运行时有效额度投影的唯一映射）。
     *
     * <p>它把「登记维度 ↔ 模板列」的对应关系也收敛到本枚举：资源包合成（{@link #matches}）与
     * 只读面展示（{@link EffectivePlanQuota#effectiveDimensions()}）因此共用同一份映射，
     * 不会出现「合成按 {@code DEVICES_MAX}、展示却指到另一列」的错位。
     *
     * @param template 冻结配额模板
     * @return 该维度在模板里的数值
     */
    public long value(PlanQuotaTemplate template) {
        return switch (this) {
            case PROJECTS_MAX -> template.projectsMax();
            case DEVICES_MAX -> template.devicesMax();
            case END_USERS_MAX -> template.endUsersMax();
            case DASHBOARDS_MAX -> template.dashboardsMax();
            case EXTERNAL_COLLABORATOR_SEATS -> template.externalCollaboratorSeats();
            case HISTORY_WINDOW -> template.historyWindowAmount();
            case UPLINK_MESSAGE_DAILY -> template.uplinkMessageDailyLimit();
            case DOWNLINK_MESSAGE_DAILY -> template.downlinkMessageDailyLimit();
            case REST_API_WRITE_DAILY -> template.restApiWriteDailyLimit();
            case REST_API_RATE_PER_MINUTE -> template.restApiRatePerMinute();
            case WEBSOCKET_CONNECTION_CONCURRENT -> template.websocketConnectionLimit();
            case SCRIPT_RULE_CONCURRENCY -> template.scriptRuleConcurrency();
            case SCRIPT_RULE_EXECUTION_DAILY -> template.scriptRuleExecutionDailyLimit();
            case SCRIPT_RULE_CPU_MILLIS_DAILY -> template.scriptRuleCpuMillisDailyLimit();
            case NOTIFICATION_DELIVERY_DAILY -> template.notificationDeliveryDailyLimit();
            case STORAGE_LIMIT -> template.storageBytesLimit();
        };
    }

    /** @return 期望计量窗口 */
    public String window() {
        return expectedWindow;
    }

    /**
     * 该维度的固定单位。
     *
     * @return 固定单位；仅 {@link #HISTORY_WINDOW} 返回 {@code null}（其单位取自基础档冻结值，
     *         下单时无法在无租户上下文时解析，因此该维度不开放购买）
     */
    public String fixedUnit() {
        return expectedUnit;
    }

    /** @return 冻结的平台安全硬上限；当前冻结记录未定义任何目录维度硬上限，故为空 */
    public OptionalLong platformHardLimit() {
        return OptionalLong.empty();
    }
}
