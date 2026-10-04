package com.things.link.project.domain.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * S14-0 product-revision-1 冻结值的领域级验收。
 *
 * <p>期望值在本测试里独立写死一次，而不是复用生产常量：两边同时改错时才会被发现，
 * 若直接比较常量与自身，漂移校验就失去意义。
 */
@DisplayName("S14-1a product-revision-1 冻结目录")
class ProductRevision1Tests {

    /** 全部四档编码与顺序。 */
    @Test
    void definitionsExposeFourFrozenTiersInDisplayOrder() {
        assertThat(ProductRevision1.definitions())
                .extracting(PlanDefinition::code)
                .containsExactly("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL");
        assertThat(ProductRevision1.CODE).isEqualTo("product-revision-1");
        assertThat(ProductRevision1.REVISION_NO).isEqualTo(1);
    }

    /** P1/P2/P3 数值必须逐值等于冻结正文，且单位与窗口可机器判定。 */
    @Test
    void dimensionValuesEqualFrozenDecisionTable() {
        assertThat(dimensions("FREE")).containsExactlyInAnyOrder(
                new PlanDimension("PROJECTS_MAX", 1, "COUNT", "NONE"),
                new PlanDimension("DEVICES_MAX", 3, "COUNT", "NONE"),
                new PlanDimension("END_USERS_MAX", 3, "COUNT", "NONE"),
                new PlanDimension("DASHBOARDS_MAX", 1, "COUNT", "NONE"),
                new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 0, "COUNT", "NONE"),
                new PlanDimension("HISTORY_WINDOW", 7, "DAY", "ROLLING"),
                new PlanDimension("UPLINK_MESSAGE_DAILY", 700, "MESSAGE", "UTC_DAY"),
                new PlanDimension("DOWNLINK_MESSAGE_DAILY", 300, "MESSAGE", "UTC_DAY"),
                new PlanDimension("REST_API_WRITE_DAILY", 10000, "REQUEST", "UTC_DAY"),
                new PlanDimension("REST_API_RATE_PER_MINUTE", 60, "REQUEST", "MINUTE"),
                new PlanDimension("WEBSOCKET_CONNECTION_CONCURRENT", 10, "CONNECTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_CONCURRENCY", 1, "EXECUTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_EXECUTION_DAILY", 10000, "EXECUTION", "UTC_DAY"),
                new PlanDimension("SCRIPT_RULE_CPU_MILLIS_DAILY", 1000, "MILLISECOND", "UTC_DAY"),
                new PlanDimension("NOTIFICATION_DELIVERY_DAILY", 1000, "DELIVERY", "UTC_DAY"),
                new PlanDimension("STORAGE_LIMIT", 100, "MB", "NONE"));

        assertThat(dimensions("STANDARD")).containsExactlyInAnyOrder(
                new PlanDimension("PROJECTS_MAX", 5, "COUNT", "NONE"),
                new PlanDimension("DEVICES_MAX", 100, "COUNT", "NONE"),
                new PlanDimension("END_USERS_MAX", 100, "COUNT", "NONE"),
                new PlanDimension("DASHBOARDS_MAX", 10, "COUNT", "NONE"),
                new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 5, "COUNT", "NONE"),
                new PlanDimension("HISTORY_WINDOW", 6, "MONTH", "ROLLING"),
                new PlanDimension("UPLINK_MESSAGE_DAILY", 105000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("DOWNLINK_MESSAGE_DAILY", 45000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("REST_API_WRITE_DAILY", 200000, "REQUEST", "UTC_DAY"),
                new PlanDimension("REST_API_RATE_PER_MINUTE", 600, "REQUEST", "MINUTE"),
                new PlanDimension("WEBSOCKET_CONNECTION_CONCURRENT", 500, "CONNECTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_CONCURRENCY", 3, "EXECUTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_EXECUTION_DAILY", 100000, "EXECUTION", "UTC_DAY"),
                new PlanDimension("SCRIPT_RULE_CPU_MILLIS_DAILY", 10000, "MILLISECOND", "UTC_DAY"),
                new PlanDimension("NOTIFICATION_DELIVERY_DAILY", 50000, "DELIVERY", "UTC_DAY"),
                new PlanDimension("STORAGE_LIMIT", 5, "GB", "NONE"));

        assertThat(dimensions("ENTERPRISE")).containsExactlyInAnyOrder(
                new PlanDimension("PROJECTS_MAX", 10, "COUNT", "NONE"),
                new PlanDimension("DEVICES_MAX", 300, "COUNT", "NONE"),
                new PlanDimension("END_USERS_MAX", 300, "COUNT", "NONE"),
                new PlanDimension("DASHBOARDS_MAX", 30, "COUNT", "NONE"),
                new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 15, "COUNT", "NONE"),
                new PlanDimension("HISTORY_WINDOW", 9, "MONTH", "ROLLING"),
                new PlanDimension("UPLINK_MESSAGE_DAILY", 315000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("DOWNLINK_MESSAGE_DAILY", 135000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("REST_API_WRITE_DAILY", 1000000, "REQUEST", "UTC_DAY"),
                new PlanDimension("REST_API_RATE_PER_MINUTE", 1800, "REQUEST", "MINUTE"),
                new PlanDimension("WEBSOCKET_CONNECTION_CONCURRENT", 2000, "CONNECTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_CONCURRENCY", 10, "EXECUTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_EXECUTION_DAILY", 500000, "EXECUTION", "UTC_DAY"),
                new PlanDimension("SCRIPT_RULE_CPU_MILLIS_DAILY", 60000, "MILLISECOND", "UTC_DAY"),
                new PlanDimension("NOTIFICATION_DELIVERY_DAILY", 200000, "DELIVERY", "UTC_DAY"),
                new PlanDimension("STORAGE_LIMIT", 20, "GB", "NONE"));

        assertThat(dimensions("PROFESSIONAL")).containsExactlyInAnyOrder(
                new PlanDimension("PROJECTS_MAX", 20, "COUNT", "NONE"),
                new PlanDimension("DEVICES_MAX", 1000, "COUNT", "NONE"),
                new PlanDimension("END_USERS_MAX", 1000, "COUNT", "NONE"),
                new PlanDimension("DASHBOARDS_MAX", 100, "COUNT", "NONE"),
                new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 50, "COUNT", "NONE"),
                new PlanDimension("HISTORY_WINDOW", 12, "MONTH", "ROLLING"),
                new PlanDimension("UPLINK_MESSAGE_DAILY", 1050000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("DOWNLINK_MESSAGE_DAILY", 450000, "MESSAGE", "UTC_DAY"),
                new PlanDimension("REST_API_WRITE_DAILY", 5000000, "REQUEST", "UTC_DAY"),
                new PlanDimension("REST_API_RATE_PER_MINUTE", 6000, "REQUEST", "MINUTE"),
                new PlanDimension("WEBSOCKET_CONNECTION_CONCURRENT", 10000, "CONNECTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_CONCURRENCY", 30, "EXECUTION", "CONCURRENT"),
                new PlanDimension("SCRIPT_RULE_EXECUTION_DAILY", 2000000, "EXECUTION", "UTC_DAY"),
                new PlanDimension("SCRIPT_RULE_CPU_MILLIS_DAILY", 300000, "MILLISECOND", "UTC_DAY"),
                new PlanDimension("NOTIFICATION_DELIVERY_DAILY", 1000000, "DELIVERY", "UTC_DAY"),
                new PlanDimension("STORAGE_LIMIT", 100, "GB", "NONE"));
    }

    /** 付费三档未定价即不得在售；FREE 是真实的 0 元在售档。 */
    @Test
    void paidTiersAreNotForSaleWithoutPriceAndFreeIsZeroPriceOnSale() {
        for (PlanDefinition definition : ProductRevision1.definitions()) {
            if ("FREE".equals(definition.code())) {
                assertThat(definition.saleStatus()).isEqualTo("ON_SALE");
                assertThat(definition.priceCents()).isZero();
                assertThat(definition.billingPeriod()).isEqualTo("NONE");
            } else {
                assertThat(definition.saleStatus()).isEqualTo("NOT_FOR_SALE");
                assertThat(definition.priceCents()).isNull();
                assertThat(definition.billingPeriod()).isEqualTo("YEAR");
            }
            assertThat(definition.currency()).isEqualTo("CNY");
        }
    }

    /** 参考价按 S14-0 §7/§9 独立于销售状态记录，且不等于成交价口径。 */
    @Test
    void referencePricesMatchArchitectureSection2SnapshotAndDoNotChangeSaleStatus() {
        assertThat(ProductRevision1.definitions())
                .extracting(PlanDefinition::code, PlanDefinition::referencePriceCents)
                .containsExactly(
                        tuple("FREE", 0L),
                        tuple("STANDARD", 298000L),
                        tuple("ENTERPRISE", 598000L),
                        tuple("PROFESSIONAL", 1198000L));
        // 参考价不改变可售事实：只有 FREE 有成交价，付费三档的成交价仍为空。
        assertThat(ProductRevision1.definitions())
                .filteredOn(definition -> definition.priceCents() != null)
                .extracting(PlanDefinition::code)
                .containsExactly("FREE");
    }

    /** 未交付能力四档一律 DISABLED，与「额度 0」分离。 */
    @Test
    void undeliveredCapabilitiesAreDisabledForEveryTier() {
        for (PlanDefinition definition : ProductRevision1.definitions()) {
            assertThat(definition.entitlements()).hasSize(12);
            assertThat(definition.entitlements())
                    .filteredOn(entitlement -> !entitlement.enabled())
                    .extracting(PlanEntitlement::code)
                    .containsExactlyInAnyOrder("SMS_CHANNEL", "OTA", "OPEN_API", "APP_SUBSCRIPTION");
        }
    }

    /** 播种校验接受与冻结定义一致的读回快照。 */
    @Test
    void verifyAcceptsFrozenDefinitions() {
        ProductRevision1.verify(ProductRevision1.definitions().stream()
                .map(ProductRevision1Tests::toEntry)
                .toList());
    }

    /** 读回快照与冻结定义不一致时必须失败，而不是被静默改写成新值。 */
    @Test
    void verifyRejectsDriftedDimensionWithoutRewritingIt() {
        PlanDefinition free = definition("FREE");
        PlanCatalogEntry drifted = toEntry(new PlanDefinition(free.code(), free.displayOrder(), free.revision(),
                free.revisionNo(), free.name(), free.saleStatus(), free.billingPeriod(), free.currency(),
                free.priceCents(), free.referencePriceCents(),
                free.dimensions().stream()
                        .map(dimension -> "PROJECTS_MAX".equals(dimension.code())
                                ? new PlanDimension("PROJECTS_MAX", 2, "COUNT", "NONE")
                                : dimension)
                        .toList(),
                free.entitlements()));

        assertThatThrownBy(() -> ProductRevision1.verify(List.of(
                drifted,
                toEntry(definition("STANDARD")),
                toEntry(definition("ENTERPRISE")),
                toEntry(definition("PROFESSIONAL")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROJECTS_MAX");
    }

    /** 缺档或出现冻结目录之外的档位都必须失败。 */
    @Test
    void verifyRejectsMissingOrUnexpectedTier() {
        assertThatThrownBy(() -> ProductRevision1.verify(List.of(toEntry(definition("FREE")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少档位 STANDARD");
        assertThatThrownBy(() -> ProductRevision1.verify(List.of(
                toEntry(definition("FREE")),
                toEntry(definition("STANDARD")),
                toEntry(definition("ENTERPRISE")),
                toEntry(definition("PROFESSIONAL")),
                toEntry(new PlanDefinition("LEGACY", 50, ProductRevision1.CODE, 1, "遗留版",
                        "NOT_FOR_SALE", "YEAR", "CNY", null, null, List.of(), List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGACY");
    }

    /** 0 只允许出现在冻结正文明确写 0 的去重协作者席位，其它维度不得用 0 表示未知。 */
    @Test
    void zeroValueIsOnlyMeaningfulForCollaboratorSeats() {
        assertThat(dimension("FREE", "EXTERNAL_COLLABORATOR_SEATS").value()).isZero();
        assertThat(ProductRevision1.definitions().stream()
                .flatMap(definition -> definition.dimensions().stream())
                .filter(dimension -> dimension.value() == 0)
                .map(PlanDimension::code)
                .distinct()
                .toList())
                .containsExactly("EXTERNAL_COLLABORATOR_SEATS");
    }

    /** 配额模板必须逐值映射冻结维度，含 P2 上下行拆分、历史窗口单位与 MB/GB→字节换算。 */
    @Test
    void quotaTemplatesMapFrozenDimensionsWithP2SplitAndHistoryWindow() {
        Map<String, PlanQuotaTemplate> templates = ProductRevision1.quotaTemplates();
        assertThat(templates.keySet()).containsExactly("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL");

        PlanQuotaTemplate free = templates.get("FREE");
        assertThat(free.projectsMax()).isEqualTo(1);
        assertThat(free.devicesMax()).isEqualTo(3);
        assertThat(free.endUsersMax()).isEqualTo(3);
        assertThat(free.dashboardsMax()).isEqualTo(1);
        assertThat(free.externalCollaboratorSeats()).isZero();
        assertThat(free.historyWindowUnit()).isEqualTo("DAY");
        assertThat(free.historyWindowAmount()).isEqualTo(7);
        assertThat(free.uplinkMessageDailyLimit()).isEqualTo(700);
        assertThat(free.downlinkMessageDailyLimit()).isEqualTo(300);
        assertThat(free.restApiWriteDailyLimit()).isEqualTo(10000);
        assertThat(free.restApiRatePerMinute()).isEqualTo(60);
        assertThat(free.websocketConnectionLimit()).isEqualTo(10);
        assertThat(free.scriptRuleConcurrency()).isEqualTo(1);
        assertThat(free.scriptRuleExecutionDailyLimit()).isEqualTo(10000);
        assertThat(free.scriptRuleCpuMillisDailyLimit()).isEqualTo(1000);
        assertThat(free.notificationDeliveryDailyLimit()).isEqualTo(1000);
        assertThat(free.storageBytesLimit()).isEqualTo(100L * 1024 * 1024);

        // P2：上行 + 下行恒等于冻结正文的公开总量，且按 floor(总量×30%) 取整。
        assertThat(p2Total(templates.get("FREE"))).isEqualTo(1000);
        assertThat(p2Total(templates.get("STANDARD"))).isEqualTo(150000);
        assertThat(p2Total(templates.get("ENTERPRISE"))).isEqualTo(450000);
        assertThat(p2Total(templates.get("PROFESSIONAL"))).isEqualTo(1500000);
        assertThat(templates.get("STANDARD").downlinkMessageDailyLimit()).isEqualTo(45000);
        assertThat(templates.get("ENTERPRISE").downlinkMessageDailyLimit()).isEqualTo(135000);
        assertThat(templates.get("PROFESSIONAL").downlinkMessageDailyLimit()).isEqualTo(450000);

        // P1-4 历史窗口单位/数量与冻结正文一致。
        assertThat(templates.get("STANDARD").historyWindowUnit()).isEqualTo("MONTH");
        assertThat(templates.get("STANDARD").historyWindowAmount()).isEqualTo(6);
        assertThat(templates.get("ENTERPRISE").historyWindowUnit()).isEqualTo("MONTH");
        assertThat(templates.get("ENTERPRISE").historyWindowAmount()).isEqualTo(9);
        assertThat(templates.get("PROFESSIONAL").historyWindowUnit()).isEqualTo("MONTH");
        assertThat(templates.get("PROFESSIONAL").historyWindowAmount()).isEqualTo(12);

        // 对象存储按 1024 进制换算：5 GB / 20 GB / 100 GB。
        assertThat(templates.get("STANDARD").storageBytesLimit()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(templates.get("ENTERPRISE").storageBytesLimit()).isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(templates.get("PROFESSIONAL").storageBytesLimit()).isEqualTo(100L * 1024 * 1024 * 1024);

        assertThat(ProductRevision1.quotaPolicyCode("FREE")).isEqualTo("PLAN_R1_FREE");
        assertThat(ProductRevision1.quotaPolicyCode("PROFESSIONAL")).isEqualTo("PLAN_R1_PROFESSIONAL");
    }

    /** 配额模板漂移必须显式失败，且不修改任何值。 */
    @Test
    void verifyQuotaTemplatesRejectsDrift() {
        ProductRevision1.verifyQuotaTemplates(ProductRevision1.quotaTemplates());

        Map<String, PlanQuotaTemplate> drifted = new java.util.LinkedHashMap<>(ProductRevision1.quotaTemplates());
        PlanQuotaTemplate free = drifted.get("FREE");
        drifted.put("FREE", new PlanQuotaTemplate(free.projectsMax() + 1, free.devicesMax(), free.endUsersMax(),
                free.dashboardsMax(), free.externalCollaboratorSeats(), free.historyWindowUnit(),
                free.historyWindowAmount(), free.uplinkMessageDailyLimit(), free.downlinkMessageDailyLimit(),
                free.restApiWriteDailyLimit(), free.restApiRatePerMinute(), free.websocketConnectionLimit(),
                free.scriptRuleConcurrency(), free.scriptRuleExecutionDailyLimit(),
                free.scriptRuleCpuMillisDailyLimit(), free.notificationDeliveryDailyLimit(),
                free.storageBytesLimit()));
        assertThatThrownBy(() -> ProductRevision1.verifyQuotaTemplates(drifted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FREE");
    }

    /** @param template 冻结模板 @return P2 上下行日额度合计 */
    private static long p2Total(PlanQuotaTemplate template) {
        return template.uplinkMessageDailyLimit() + template.downlinkMessageDailyLimit();
    }

    /** @param code 档位编码 @return 冻结定义 */
    private static PlanDefinition definition(String code) {
        return ProductRevision1.definitions().stream()
                .filter(definition -> definition.code().equals(code))
                .findFirst().orElseThrow();
    }

    /** @param code 档位编码 @return 该档冻结维度 */
    private static List<PlanDimension> dimensions(String code) {
        return definition(code).dimensions();
    }

    /** @param code 档位编码 @param dimensionCode 维度编码 @return 冻结维度 */
    private static PlanDimension dimension(String code, String dimensionCode) {
        return dimensions(code).stream()
                .filter(dimension -> dimension.code().equals(dimensionCode))
                .findFirst().orElseThrow();
    }

    /** @param definition 冻结定义 @return 携带固定生效时刻的目录快照 */
    private static PlanCatalogEntry toEntry(PlanDefinition definition) {
        return new PlanCatalogEntry(definition.code(), definition.displayOrder(), definition.revision(),
                definition.revisionNo(), definition.name(), definition.saleStatus(), definition.billingPeriod(),
                definition.currency(), definition.priceCents(), definition.referencePriceCents(),
                definition.referencePriceCents() == null ? null : definition.currency(),
                Instant.parse("2026-09-12T00:00:00Z"), null,
                definition.dimensions(), definition.entitlements());
    }
}
