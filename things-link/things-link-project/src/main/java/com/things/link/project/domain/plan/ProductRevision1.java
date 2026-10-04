package com.things.link.project.domain.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code product-revision-1} 的机器可执行冻结值（S14-0 decisionVersion 1）。
 *
 * <p>这些常量是目录播种的输入，也是漂移校验的基准。它们与迁移
 * {@code V20260912_0240__plan_catalog_product_revision_1_seed.sql} 必须逐值一致：
 * 播种后由 {@link #verify(List)} 读回数据库比对，任何不一致都以异常显式失败，
 * <b>绝不原地改写已存在的修订版</b>。价格口径按负责人 2026-09-16 确认的选项 (b)：
 * 参考年价只作展示快照，付费三档一律 {@code NOT_FOR_SALE} 且无成交价，正式售价等 S14-5 前再定；
 * 四档参考价按 S14-0 §7/§9 记为 ￥0 / ￥2,980 / ￥5,980 / ￥11,980
 * （人民币分 0 / 298000 / 598000 / 1198000）。**参考价不等于可售价格**：它不改变销售状态，
 * 也不得被当成成交价或参与任何销售/支付判定。
 *
 * <p>P1 的「单账号参与外部项目安全上限 50」和 P3 的「单对象大小」是平台安全常量，
 * 冻结正文明确它们不是可售权益（任何档位不得突破），因此不进入目录。
 */
public final class ProductRevision1 {

    /** 冻结的产品修订版标识。 */
    public static final String CODE = "product-revision-1";
    /** FREE 档位编码；注册默认免费订阅只认这一档（S14-2a）。 */
    public static final String FREE_PLAN_CODE = "FREE";
    /** 冻结的修订序号。 */
    public static final int REVISION_NO = 1;
    /** 配额模板编码前缀；完整编码为前缀加档位编码，如 {@code PLAN_R1_FREE}。 */
    public static final String QUOTA_POLICY_CODE_PREFIX = "PLAN_R1_";
    /** FREE 的参考价（人民币分），与商业架构 §2 参考年价一致。 */
    public static final long FREE_REFERENCE_PRICE_CENTS = 0L;
    /** STANDARD 的参考价（人民币分）。 */
    public static final long STANDARD_REFERENCE_PRICE_CENTS = 298000L;
    /** ENTERPRISE 的参考价（人民币分）。 */
    public static final long ENTERPRISE_REFERENCE_PRICE_CENTS = 598000L;
    /** PROFESSIONAL 的参考价（人民币分）。 */
    public static final long PROFESSIONAL_REFERENCE_PRICE_CENTS = 1198000L;
    /** 1 MB 的字节数；与 S7 FREE 模板的 1073741824 = 1 GiB 一样采用 1024 进制。 */
    private static final long BYTES_PER_MB = 1024L * 1024L;
    /** 1 GB 的字节数。 */
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    /** 已交付且四档都包含的八项能力；数值额度在维度行里。 */
    private static final List<String> DELIVERED_CAPABILITIES = List.of(
            "REST_API_RATE_LIMIT", "WEBSOCKET_CONNECTION", "SCRIPT_RULE_CONCURRENCY",
            "SCRIPT_RULE_EXECUTION", "SCRIPT_RULE_CPU", "NOTIFICATION_DELIVERY",
            "OBJECT_STORAGE", "REST_API_WRITE");
    /** 未交付能力：真实短信渠道、OTA、开放 API、应用订阅，四档一律 DISABLED。 */
    private static final List<String> UNDELIVERED_CAPABILITIES = List.of(
            "APP_SUBSCRIPTION", "OPEN_API", "OTA", "SMS_CHANNEL");

    /** 四档冻结定义，按定价页展示顺序。 */
    private static final List<PlanDefinition> DEFINITIONS = List.of(
            free(), standard(), enterprise(), professional());

    private ProductRevision1() {
    }

    /**
     * 四档冻结定义。
     *
     * @return 不可变定义列表，按展示顺序
     */
    public static List<PlanDefinition> definitions() {
        return DEFINITIONS;
    }

    /**
     * 四档冻结配额模板：把 {@link #definitions()} 的 16 个数值维度映射到 {@code sys_quota_policy} 列。
     *
     * <p>映射是「目录冻结值 → 运行时策略模板」的唯一入口，播种、漂移校验与集成测试都以它为准。
     * 对象存储在这里按 1024 进制换算成字节，其余维度原样取值。
     *
     * @return 档位编码到冻结模板的不可变映射，按展示顺序
     */
    public static Map<String, PlanQuotaTemplate> quotaTemplates() {
        Map<String, PlanQuotaTemplate> templates = new LinkedHashMap<>();
        DEFINITIONS.forEach(definition -> templates.put(definition.code(), quotaTemplate(definition)));
        return Collections.unmodifiableMap(templates);
    }

    /**
     * 档位编码对应的配额模板编码。
     *
     * @param planCode 档位编码，如 {@code FREE}
     * @return 全局唯一的模板编码，如 {@code PLAN_R1_FREE}
     */
    public static String quotaPolicyCode(String planCode) {
        return QUOTA_POLICY_CODE_PREFIX + planCode;
    }

    /**
     * 校验读回的配额模板与冻结的 16 个维度逐值一致。
     *
     * <p>只读校验、绝不改写：任何缺失、多余或数值差异都抛出 {@link IllegalStateException}，
     * 让「模板被静默改动」当场失败，而不是把漂移带进运行时。
     *
     * @param actual 指定产品修订版读回的模板快照，键为档位编码
     * @throws IllegalStateException 档位数量或任一冻结维度与目录定义不一致
     */
    public static void verifyQuotaTemplates(Map<String, PlanQuotaTemplate> actual) {
        List<String> differences = new ArrayList<>();
        Map<String, PlanQuotaTemplate> expected = quotaTemplates();
        for (Map.Entry<String, PlanQuotaTemplate> entry : expected.entrySet()) {
            PlanQuotaTemplate found = actual.get(entry.getKey());
            if (found == null) {
                differences.add("缺少档位模板 " + entry.getKey());
            } else if (!entry.getValue().equals(found)) {
                differences.add("档位模板 " + entry.getKey() + " 与冻结值不一致: 期望 " + entry.getValue()
                        + "，实际 " + found);
            }
        }
        actual.keySet().stream().filter(code -> !expected.containsKey(code))
                .forEach(code -> differences.add("出现冻结目录之外的档位模板 " + code));
        if (!differences.isEmpty()) {
            throw new IllegalStateException(
                    CODE + " 配额模板与冻结定义不一致，禁止静默改值: " + String.join("; ", differences));
        }
    }

    /**
     * 把一个档位的冻结定义映射成配额模板。
     *
     * @param definition 冻结定义
     * @return 冻结模板
     */
    private static PlanQuotaTemplate quotaTemplate(PlanDefinition definition) {
        PlanDimension historyWindow = requireDimension(definition, "HISTORY_WINDOW");
        PlanDimension storage = requireDimension(definition, "STORAGE_LIMIT");
        return new PlanQuotaTemplate(
                requireDimension(definition, "PROJECTS_MAX").value(),
                requireDimension(definition, "DEVICES_MAX").value(),
                requireDimension(definition, "END_USERS_MAX").value(),
                requireDimension(definition, "DASHBOARDS_MAX").value(),
                requireDimension(definition, "EXTERNAL_COLLABORATOR_SEATS").value(),
                historyWindow.unit(),
                historyWindow.value(),
                requireDimension(definition, "UPLINK_MESSAGE_DAILY").value(),
                requireDimension(definition, "DOWNLINK_MESSAGE_DAILY").value(),
                requireDimension(definition, "REST_API_WRITE_DAILY").value(),
                requireDimension(definition, "REST_API_RATE_PER_MINUTE").value(),
                requireDimension(definition, "WEBSOCKET_CONNECTION_CONCURRENT").value(),
                requireDimension(definition, "SCRIPT_RULE_CONCURRENCY").value(),
                requireDimension(definition, "SCRIPT_RULE_EXECUTION_DAILY").value(),
                requireDimension(definition, "SCRIPT_RULE_CPU_MILLIS_DAILY").value(),
                requireDimension(definition, "NOTIFICATION_DELIVERY_DAILY").value(),
                storageBytes(storage));
    }

    /**
     * 取指定冻结维度，缺失即失败（目录定义本身不完整时不允许静默跳过）。
     *
     * @param definition 冻结定义
     * @param code 维度编码
     * @return 冻结维度
     */
    private static PlanDimension requireDimension(PlanDefinition definition, String code) {
        return definition.dimensions().stream()
                .filter(dimension -> dimension.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        definition.code() + " 缺少冻结维度 " + code));
    }

    /**
     * 把对象存储冻结值（{@code STORAGE_LIMIT} 的数值 + MB/GB 单位）换算成字节。
     *
     * <p>采用 1024 进制，与 S7 FREE 模板的 1073741824（1 GiB）保持一致；未知单位直接失败，
     * 不猜测换算规则。
     *
     * @param storage 冻结的对象存储维度
     * @return 字节数
     */
    private static long storageBytes(PlanDimension storage) {
        return switch (storage.unit()) {
            case "MB" -> storage.value() * BYTES_PER_MB;
            case "GB" -> storage.value() * BYTES_PER_GB;
            default -> throw new IllegalStateException("对象存储冻结单位无法换算为字节: " + storage.unit());
        };
    }

    /**
     * 校验读回的目录与冻结定义逐值一致。
     *
     * <p>只读校验、不改写：不一致时抛出 {@link IllegalStateException}（包含全部差异项），
     * 让「播种悄悄改了值」立即失败，而不是把漂移带进运行时。
     *
     * @param actual 指定产品修订版的读回快照
     * @throws IllegalStateException 档位数量、报价字段、维度或权益与冻结定义不一致
     */
    public static void verify(List<PlanCatalogEntry> actual) {
        List<String> differences = new ArrayList<>();
        Map<String, PlanCatalogEntry> remaining = actual.stream().collect(Collectors.toMap(
                PlanCatalogEntry::code, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        for (PlanDefinition definition : DEFINITIONS) {
            PlanCatalogEntry entry = remaining.remove(definition.code());
            if (entry == null) {
                differences.add("缺少档位 " + definition.code());
                continue;
            }
            compare(differences, definition, entry);
        }
        for (String code : remaining.keySet()) {
            differences.add("出现冻结目录之外的档位 " + code);
        }
        if (!differences.isEmpty()) {
            throw new IllegalStateException(
                    CODE + " 目录与冻结定义不一致，禁止静默改值: " + String.join("; ", differences));
        }
    }

    /**
     * 比对单个档位的报价字段、维度与权益。
     *
     * @param differences 差异收集器
     * @param definition 冻结定义
     * @param entry 数据库读回快照
     */
    private static void compare(List<String> differences, PlanDefinition definition, PlanCatalogEntry entry) {
        String prefix = definition.code() + ": ";
        if (definition.displayOrder() != entry.displayOrder()) {
            differences.add(prefix + "展示顺序不一致");
        }
        if (!definition.revision().equals(entry.revision()) || definition.revisionNo() != entry.revisionNo()) {
            differences.add(prefix + "修订版标识不一致");
        }
        if (!definition.name().equals(entry.name())) {
            differences.add(prefix + "名称不一致");
        }
        if (!definition.saleStatus().equals(entry.saleStatus())) {
            differences.add(prefix + "销售状态不一致");
        }
        if (!definition.billingPeriod().equals(entry.billingPeriod())) {
            differences.add(prefix + "计费周期不一致");
        }
        if (!definition.currency().equals(entry.currency())) {
            differences.add(prefix + "币种不一致");
        }
        if (!Objects.equals(definition.priceCents(), entry.priceCents())) {
            differences.add(prefix + "成交价不一致");
        }
        if (!Objects.equals(definition.referencePriceCents(), entry.referencePriceCents())
                || (definition.referencePriceCents() != null && !definition.currency().equals(entry.referencePriceCurrency()))) {
            differences.add(prefix + "参考价不一致");
        }
        compareDimensions(differences, prefix, definition, entry);
        compareEntitlements(differences, prefix, definition, entry);
    }

    /**
     * 比对数值维度集合：缺项、多项与逐值差异都要报出。
     *
     * @param differences 差异收集器
     * @param prefix 档位前缀
     * @param definition 冻结定义
     * @param entry 数据库读回快照
     */
    private static void compareDimensions(List<String> differences, String prefix,
                                          PlanDefinition definition, PlanCatalogEntry entry) {
        Map<String, PlanDimension> expected = definition.dimensions().stream()
                .collect(Collectors.toMap(PlanDimension::code, Function.identity()));
        Map<String, PlanDimension> actual = entry.dimensions().stream()
                .collect(Collectors.toMap(PlanDimension::code, Function.identity()));
        for (Map.Entry<String, PlanDimension> dimension : expected.entrySet()) {
            PlanDimension found = actual.get(dimension.getKey());
            if (found == null) {
                differences.add(prefix + "缺少维度 " + dimension.getKey());
            } else if (!dimension.getValue().equals(found)) {
                differences.add(prefix + "维度 " + dimension.getKey() + " 数值/单位/窗口不一致");
            }
        }
        actual.keySet().stream().filter(code -> !expected.containsKey(code))
                .forEach(code -> differences.add(prefix + "出现冻结目录之外的维度 " + code));
    }

    /**
     * 比对功能权益集合：ENABLED/DISABLED 必须与冻结正文一致。
     *
     * @param differences 差异收集器
     * @param prefix 档位前缀
     * @param definition 冻结定义
     * @param entry 数据库读回快照
     */
    private static void compareEntitlements(List<String> differences, String prefix,
                                            PlanDefinition definition, PlanCatalogEntry entry) {
        Map<String, Boolean> expected = definition.entitlements().stream()
                .collect(Collectors.toMap(PlanEntitlement::code, PlanEntitlement::enabled));
        Map<String, Boolean> actual = entry.entitlements().stream()
                .collect(Collectors.toMap(PlanEntitlement::code, PlanEntitlement::enabled));
        for (Map.Entry<String, Boolean> entitlement : expected.entrySet()) {
            Boolean found = actual.get(entitlement.getKey());
            if (found == null) {
                differences.add(prefix + "缺少权益 " + entitlement.getKey());
            } else if (!entitlement.getValue().equals(found)) {
                differences.add(prefix + "权益 " + entitlement.getKey() + " 状态不一致");
            }
        }
        actual.keySet().stream().filter(code -> !expected.containsKey(code))
                .forEach(code -> differences.add(prefix + "出现冻结目录之外的权益 " + code));
    }

    /** @return FREE：1 项目 / 3 设备 / 3 终端用户 / 1 看板 / 0 席位 / 7 天 / 700+300，0 元在售，参考价 ￥0 */
    private static PlanDefinition free() {
        return new PlanDefinition(FREE_PLAN_CODE, 10, CODE, REVISION_NO, "免费版", "ON_SALE", "NONE", "CNY", 0L,
                FREE_REFERENCE_PRICE_CENTS,
                withCostDimensions(List.of(
                        new PlanDimension("PROJECTS_MAX", 1, "COUNT", "NONE"),
                        new PlanDimension("DEVICES_MAX", 3, "COUNT", "NONE"),
                        new PlanDimension("END_USERS_MAX", 3, "COUNT", "NONE"),
                        new PlanDimension("DASHBOARDS_MAX", 1, "COUNT", "NONE"),
                        new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 0, "COUNT", "NONE"),
                        new PlanDimension("HISTORY_WINDOW", 7, "DAY", "ROLLING"),
                        new PlanDimension("UPLINK_MESSAGE_DAILY", 700, "MESSAGE", "UTC_DAY"),
                        new PlanDimension("DOWNLINK_MESSAGE_DAILY", 300, "MESSAGE", "UTC_DAY")),
                        10000, 60, 10, 1, 10000, 1000, 1000, 100, "MB"),
                entitlements());
    }

    /** @return STANDARD：5 / 100 / 100 / 10 / 5 席位 / 6 个月 / 105000+45000，未定价未开售，参考价 ￥2,980 */
    private static PlanDefinition standard() {
        return new PlanDefinition("STANDARD", 20, CODE, REVISION_NO, "标准版", "NOT_FOR_SALE", "YEAR", "CNY", null,
                STANDARD_REFERENCE_PRICE_CENTS,
                withCostDimensions(List.of(
                        new PlanDimension("PROJECTS_MAX", 5, "COUNT", "NONE"),
                        new PlanDimension("DEVICES_MAX", 100, "COUNT", "NONE"),
                        new PlanDimension("END_USERS_MAX", 100, "COUNT", "NONE"),
                        new PlanDimension("DASHBOARDS_MAX", 10, "COUNT", "NONE"),
                        new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 5, "COUNT", "NONE"),
                        new PlanDimension("HISTORY_WINDOW", 6, "MONTH", "ROLLING"),
                        new PlanDimension("UPLINK_MESSAGE_DAILY", 105000, "MESSAGE", "UTC_DAY"),
                        new PlanDimension("DOWNLINK_MESSAGE_DAILY", 45000, "MESSAGE", "UTC_DAY")),
                        200000, 600, 500, 3, 100000, 10000, 50000, 5, "GB"),
                entitlements());
    }

    /** @return ENTERPRISE：10 / 300 / 300 / 30 / 15 席位 / 9 个月 / 315000+135000，未定价未开售，参考价 ￥5,980 */
    private static PlanDefinition enterprise() {
        return new PlanDefinition("ENTERPRISE", 30, CODE, REVISION_NO, "企业版", "NOT_FOR_SALE", "YEAR", "CNY", null,
                ENTERPRISE_REFERENCE_PRICE_CENTS,
                withCostDimensions(List.of(
                        new PlanDimension("PROJECTS_MAX", 10, "COUNT", "NONE"),
                        new PlanDimension("DEVICES_MAX", 300, "COUNT", "NONE"),
                        new PlanDimension("END_USERS_MAX", 300, "COUNT", "NONE"),
                        new PlanDimension("DASHBOARDS_MAX", 30, "COUNT", "NONE"),
                        new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 15, "COUNT", "NONE"),
                        new PlanDimension("HISTORY_WINDOW", 9, "MONTH", "ROLLING"),
                        new PlanDimension("UPLINK_MESSAGE_DAILY", 315000, "MESSAGE", "UTC_DAY"),
                        new PlanDimension("DOWNLINK_MESSAGE_DAILY", 135000, "MESSAGE", "UTC_DAY")),
                        1000000, 1800, 2000, 10, 500000, 60000, 200000, 20, "GB"),
                entitlements());
    }

    /** @return PROFESSIONAL：20 / 1000 / 1000 / 100 / 50 席位 / 12 个月 / 1050000+450000，未定价未开售，参考价 ￥11,980 */
    private static PlanDefinition professional() {
        return new PlanDefinition("PROFESSIONAL", 40, CODE, REVISION_NO, "专业版", "NOT_FOR_SALE", "YEAR", "CNY", null,
                PROFESSIONAL_REFERENCE_PRICE_CENTS,
                withCostDimensions(List.of(
                        new PlanDimension("PROJECTS_MAX", 20, "COUNT", "NONE"),
                        new PlanDimension("DEVICES_MAX", 1000, "COUNT", "NONE"),
                        new PlanDimension("END_USERS_MAX", 1000, "COUNT", "NONE"),
                        new PlanDimension("DASHBOARDS_MAX", 100, "COUNT", "NONE"),
                        new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 50, "COUNT", "NONE"),
                        new PlanDimension("HISTORY_WINDOW", 12, "MONTH", "ROLLING"),
                        new PlanDimension("UPLINK_MESSAGE_DAILY", 1050000, "MESSAGE", "UTC_DAY"),
                        new PlanDimension("DOWNLINK_MESSAGE_DAILY", 450000, "MESSAGE", "UTC_DAY")),
                        5000000, 6000, 10000, 30, 2000000, 300000, 1000000, 100, "GB"),
                entitlements());
    }

    /**
     * 把 P3 的成本型额度追加到 P1/P2 维度之后。
     *
     * <p>对象存储按冻结正文原样落库为 MB/GB 数值，不在目录层替运行时选择 1000/1024 换算；
     * 换算规则属于后续切片，目录只保存冻结文本里的确定值。
     *
     * @param base P1/P2 冻结维度
     * @param restWriteDaily REST 写请求/UTC 日
     * @param restRatePerMinute REST 速率/分钟
     * @param websocketConcurrent WebSocket 并发连接
     * @param scriptConcurrency 脚本/规则并发
     * @param scriptExecutionDaily 脚本/规则执行次数/UTC 日
     * @param scriptCpuMillisDaily 脚本/规则 CPU 毫秒/UTC 日
     * @param notificationDaily 通知投递尝试/UTC 日
     * @param storage 对象存储额度
     * @param storageUnit 对象存储单位（MB/GB）
     * @return 完整维度列表
     */
    private static List<PlanDimension> withCostDimensions(List<PlanDimension> base,
            long restWriteDaily, long restRatePerMinute, long websocketConcurrent, long scriptConcurrency,
            long scriptExecutionDaily, long scriptCpuMillisDaily, long notificationDaily,
            long storage, String storageUnit) {
        List<PlanDimension> dimensions = new ArrayList<>(base);
        dimensions.add(new PlanDimension("REST_API_WRITE_DAILY", restWriteDaily, "REQUEST", "UTC_DAY"));
        dimensions.add(new PlanDimension("REST_API_RATE_PER_MINUTE", restRatePerMinute, "REQUEST", "MINUTE"));
        dimensions.add(new PlanDimension(
                "WEBSOCKET_CONNECTION_CONCURRENT", websocketConcurrent, "CONNECTION", "CONCURRENT"));
        dimensions.add(new PlanDimension("SCRIPT_RULE_CONCURRENCY", scriptConcurrency, "EXECUTION", "CONCURRENT"));
        dimensions.add(new PlanDimension(
                "SCRIPT_RULE_EXECUTION_DAILY", scriptExecutionDaily, "EXECUTION", "UTC_DAY"));
        dimensions.add(new PlanDimension(
                "SCRIPT_RULE_CPU_MILLIS_DAILY", scriptCpuMillisDaily, "MILLISECOND", "UTC_DAY"));
        dimensions.add(new PlanDimension("NOTIFICATION_DELIVERY_DAILY", notificationDaily, "DELIVERY", "UTC_DAY"));
        dimensions.add(new PlanDimension("STORAGE_LIMIT", storage, storageUnit, "NONE"));
        return List.copyOf(dimensions);
    }

    /** @return 四档共有的十二项权益：八项已交付、四项未交付固定 DISABLED */
    private static List<PlanEntitlement> entitlements() {
        List<PlanEntitlement> entitlements = new ArrayList<>();
        DELIVERED_CAPABILITIES.forEach(code -> entitlements.add(new PlanEntitlement(code, true)));
        UNDELIVERED_CAPABILITIES.forEach(code -> entitlements.add(new PlanEntitlement(code, false)));
        return List.copyOf(entitlements);
    }
}
