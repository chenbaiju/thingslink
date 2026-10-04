package com.things.link.project.domain.plan;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 已解析到具体 {@code sys_quota_policy} 行的有效配额投影（S14-1b；S14-4a 增加资源包合成）。
 *
 * <p>它把「哪一份模板」与「模板里的冻结值」绑在一起：业务入口读 {@link #projectsMax()} 等
 * 维度即可，不需要知道 plan code，也不需要自己判断档位。数值全部来自同一行的同一快照，
 * 不会出现「项目数用新修订版、消息额度用旧修订版」的拼接。
 *
 * <h2>S14-4a：有效值 = 基础档 + 有效资源包（P6）</h2>
 * {@link #template()} 始终是**基础档冻结模板**（目录不可变快照，也是漂移校验基准）；
 * 各维度访问器返回的是 {@link #effectiveTemplate()} 的合成值 = 基础值 + 同维度、同单位、
 * 同窗口的有效包加数之和，再按平台硬上限封顶（当前冻结未定义目录维度硬上限，机制保留）。
 * 包提高的是**上限**而不是余额，因此不存在结转：下个窗口的有效值是同一个合成上限。
 *
 * <p>合成是纯函数：不匹配的包加数被忽略而不是取整；没有加数时有效模板就是基础模板本身
 * （对象恒等，便于既有断言与缓存比较）。
 *
 * <p>{@code disabled} 能力不在这里表达：未交付能力没有数值额度，只有 {@link PlanEntitlement}
 * 的 {@code DISABLED} 状态；不得用这里的 {@code 0} 冒充未交付。
 *
 * @param policyId 配额模板 ID（{@code sys_quota_policy.id}）
 * @param policyVersion 模板单调版本，用于缓存失效排序
 * @param template 该模板承载的冻结配额模板（基础档，不含资源包）
 * @param effectiveTemplate 基础档 + 有效包加数（并按平台硬上限封顶）后的实际生效模板
 * @param packageAdditions 参与合成的包加数，按维度编码索引；没有包时为空映射
 */
public record EffectivePlanQuota(
        UUID policyId,
        long policyVersion,
        PlanQuotaTemplate template,
        PlanQuotaTemplate effectiveTemplate,
        Map<String, Long> packageAdditions) {

    /** 模板身份与内容都必须确定；有效模板不得为空，加数必须为正。 */
    public EffectivePlanQuota {
        Objects.requireNonNull(policyId, "配额模板 ID 不得为空");
        Objects.requireNonNull(template, "冻结配额模板不得为空");
        Objects.requireNonNull(effectiveTemplate, "有效配额模板不得为空");
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("配额模板版本必须为正数");
        }
        packageAdditions = Map.copyOf(packageAdditions);
        packageAdditions.forEach((code, amount) -> {
            if (code == null || amount == null || amount <= 0) {
                throw new IllegalArgumentException("资源包加数必须按正数编码索引");
            }
        });
    }

    /**
     * 兼容 S14-1b 的三参构造点：没有资源包时有效模板就是基础模板。
     *
     * @param policyId 配额模板 ID
     * @param policyVersion 模板单调版本
     * @param template 冻结配额模板
     */
    public EffectivePlanQuota(UUID policyId, long policyVersion, PlanQuotaTemplate template) {
        this(policyId, policyVersion, template, template, Map.of());
    }

    /**
     * 把有效资源包加数合成到基础配额投影上（S14-4a 的唯一合成入口）。
     *
     * <p>只接受维度同名且单位、窗口都与 {@code base.template} 一致、且登记为可扩容维度的加数；
     * 其余加数被忽略。没有可用加数时返回 {@code base} 本身。
     *
     * @param base 基础档冻结配额投影
     * @param additions 仓库读取的有效包加数（可含不匹配的维度/单位/窗口）
     * @return 合成后的有效配额投影
     */
    public static EffectivePlanQuota compose(EffectivePlanQuota base, List<ResourcePackageAddition> additions) {
        Objects.requireNonNull(base, "基础配额投影不得为空");
        Objects.requireNonNull(additions, "资源包加数不得为空");
        EnumMap<ResourcePackageDimension, Long> sums = new EnumMap<>(ResourcePackageDimension.class);
        for (ResourcePackageAddition addition : additions) {
            ResourcePackageDimension.fromCode(addition.dimensionCode())
                    .filter(dimension -> dimension.matches(addition, base.template))
                    .ifPresent(dimension -> sums.merge(dimension, addition.amount(), Long::sum));
        }
        if (sums.isEmpty()) {
            return base;
        }
        PlanQuotaTemplate frozen = base.template;
        PlanQuotaTemplate effective = new PlanQuotaTemplate(
                add(frozen.projectsMax(), sums, ResourcePackageDimension.PROJECTS_MAX),
                add(frozen.devicesMax(), sums, ResourcePackageDimension.DEVICES_MAX),
                add(frozen.endUsersMax(), sums, ResourcePackageDimension.END_USERS_MAX),
                add(frozen.dashboardsMax(), sums, ResourcePackageDimension.DASHBOARDS_MAX),
                add(frozen.externalCollaboratorSeats(), sums,
                        ResourcePackageDimension.EXTERNAL_COLLABORATOR_SEATS),
                frozen.historyWindowUnit(),
                add(frozen.historyWindowAmount(), sums, ResourcePackageDimension.HISTORY_WINDOW),
                add(frozen.uplinkMessageDailyLimit(), sums, ResourcePackageDimension.UPLINK_MESSAGE_DAILY),
                add(frozen.downlinkMessageDailyLimit(), sums, ResourcePackageDimension.DOWNLINK_MESSAGE_DAILY),
                add(frozen.restApiWriteDailyLimit(), sums, ResourcePackageDimension.REST_API_WRITE_DAILY),
                add(frozen.restApiRatePerMinute(), sums, ResourcePackageDimension.REST_API_RATE_PER_MINUTE),
                add(frozen.websocketConnectionLimit(), sums,
                        ResourcePackageDimension.WEBSOCKET_CONNECTION_CONCURRENT),
                add(frozen.scriptRuleConcurrency(), sums, ResourcePackageDimension.SCRIPT_RULE_CONCURRENCY),
                add(frozen.scriptRuleExecutionDailyLimit(), sums,
                        ResourcePackageDimension.SCRIPT_RULE_EXECUTION_DAILY),
                add(frozen.scriptRuleCpuMillisDailyLimit(), sums,
                        ResourcePackageDimension.SCRIPT_RULE_CPU_MILLIS_DAILY),
                add(frozen.notificationDeliveryDailyLimit(), sums,
                        ResourcePackageDimension.NOTIFICATION_DELIVERY_DAILY),
                add(frozen.storageBytesLimit(), sums, ResourcePackageDimension.STORAGE_LIMIT));
        Map<String, Long> byCode = new LinkedHashMap<>();
        sums.forEach((dimension, amount) -> byCode.put(dimension.name(), amount));
        return new EffectivePlanQuota(base.policyId, base.policyVersion, frozen, effective,
                Map.copyOf(byCode));
    }

    /**
     * 基础值 + 加数，并按该维度的平台硬上限封顶。
     *
     * @param base 基础档数值
     * @param sums 按登记维度索引的加数
     * @param dimension 目标维度
     * @return 合成值；无加数时原样返回基础值
     */
    private static long add(long base, EnumMap<ResourcePackageDimension, Long> sums,
                            ResourcePackageDimension dimension) {
        Long addition = sums.get(dimension);
        if (addition == null || addition <= 0) {
            return base;
        }
        long total = addition > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + addition;
        OptionalLong hardLimit = dimension.platformHardLimit();
        return hardLimit.isPresent() ? Math.min(total, hardLimit.getAsLong()) : total;
    }

    /** @return 该修订版冻结的基础配额模板（不含资源包） */
    public PlanQuotaTemplate frozenTemplate() {
        return template;
    }

    /** @return 基础档 + 有效资源包后的实际生效配额模板 */
    public PlanQuotaTemplate effectiveTemplate() {
        return effectiveTemplate;
    }

    /**
     * 把有效模板渲染成**运行时单位**的维度行（S14-4c 只读面）。
     *
     * <p>它回答「此刻真正生效的额度是多少」，单位与窗口取自
     * {@link ResourcePackageDimension}（与合成、与真实准入同一份映射），因此可以与强制面
     * 逐值对照。注意它与目录冻结表示**不是同一个表示**：目录把对象存储写成
     * {@code 100 MB}／{@code 5 GB}，这里写 {@code BYTE}，两者不得互相冒充。
     *
     * @return 按维度编码升序排列的运行时维度行，数值已含有效资源包与人工调整
     */
    public List<PlanDimension> effectiveDimensions() {
        return Arrays.stream(ResourcePackageDimension.values())
                .sorted(Comparator.comparing(Enum::name))
                .map(dimension -> new PlanDimension(dimension.name(), dimension.value(effectiveTemplate),
                        dimension.unit(effectiveTemplate), dimension.window()))
                .toList();
    }

    /** @return 是否存在生效的资源包加数 */
    public boolean hasPackageAdditions() {
        return !packageAdditions.isEmpty();
    }

    /** @return 冻结维度 {@code PROJECTS_MAX}（含有效资源包） */
    public long projectsMax() {
        return effectiveTemplate.projectsMax();
    }

    /** @return 冻结维度 {@code DEVICES_MAX}（含有效资源包） */
    public long devicesMax() {
        return effectiveTemplate.devicesMax();
    }

    /** @return 冻结维度 {@code END_USERS_MAX}（含有效资源包） */
    public long endUsersMax() {
        return effectiveTemplate.endUsersMax();
    }

    /** @return 冻结维度 {@code DASHBOARDS_MAX}（含有效资源包） */
    public long dashboardsMax() {
        return effectiveTemplate.dashboardsMax();
    }

    /** @return 冻结维度 {@code EXTERNAL_COLLABORATOR_SEATS}（含有效资源包） */
    public long externalCollaboratorSeats() {
        return effectiveTemplate.externalCollaboratorSeats();
    }

    /** @return 冻结维度 {@code HISTORY_WINDOW} 的单位 */
    public String historyWindowUnit() {
        return effectiveTemplate.historyWindowUnit();
    }

    /** @return 冻结维度 {@code HISTORY_WINDOW} 的数量（含有效资源包） */
    public long historyWindowAmount() {
        return effectiveTemplate.historyWindowAmount();
    }

    /** @return 冻结维度 {@code UPLINK_MESSAGE_DAILY}（含有效资源包） */
    public long uplinkMessageDailyLimit() {
        return effectiveTemplate.uplinkMessageDailyLimit();
    }

    /** @return 冻结维度 {@code DOWNLINK_MESSAGE_DAILY}（含有效资源包） */
    public long downlinkMessageDailyLimit() {
        return effectiveTemplate.downlinkMessageDailyLimit();
    }

    /** @return 冻结维度 {@code REST_API_WRITE_DAILY}（含有效资源包） */
    public long restApiWriteDailyLimit() {
        return effectiveTemplate.restApiWriteDailyLimit();
    }

    /** @return 冻结维度 {@code REST_API_RATE_PER_MINUTE}（含有效资源包） */
    public long restApiRatePerMinute() {
        return effectiveTemplate.restApiRatePerMinute();
    }

    /** @return 冻结维度 {@code WEBSOCKET_CONNECTION_CONCURRENT}（含有效资源包） */
    public long websocketConnectionLimit() {
        return effectiveTemplate.websocketConnectionLimit();
    }

    /** @return 冻结维度 {@code SCRIPT_RULE_CONCURRENCY}（含有效资源包） */
    public long scriptRuleConcurrency() {
        return effectiveTemplate.scriptRuleConcurrency();
    }

    /** @return 冻结维度 {@code SCRIPT_RULE_EXECUTION_DAILY}（含有效资源包） */
    public long scriptRuleExecutionDailyLimit() {
        return effectiveTemplate.scriptRuleExecutionDailyLimit();
    }

    /** @return 冻结维度 {@code SCRIPT_RULE_CPU_MILLIS_DAILY}（含有效资源包） */
    public long scriptRuleCpuMillisDailyLimit() {
        return effectiveTemplate.scriptRuleCpuMillisDailyLimit();
    }

    /** @return 冻结维度 {@code NOTIFICATION_DELIVERY_DAILY}（含有效资源包） */
    public long notificationDeliveryDailyLimit() {
        return effectiveTemplate.notificationDeliveryDailyLimit();
    }

    /** @return 冻结维度 {@code STORAGE_LIMIT} 换算后的字节数（含有效资源包） */
    public long storageBytesLimit() {
        return effectiveTemplate.storageBytesLimit();
    }
}
