package com.things.link.bootstrap.project.plan;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.plan.PlanCatalogSeedService;
import com.things.link.project.domain.plan.PlanDefinition;
import com.things.link.project.domain.plan.PlanDimension;
import com.things.link.project.domain.plan.ProductRevision1;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S14-1a 商用套餐目录的真实 PostgreSQL 与 HTTP 验收。
 *
 * <p>这里同时钉住四件事：冻结值逐值落库、未定价档位没有价格、未交付能力是 DISABLED 而不是
 * 0 额度、已发布修订版在数据库层不可原地修改或删除。HTTP 部分验证平台目录端点使用现有
 * 认证守卫（无令牌 401），响应不含 {@code null}（不把未知表达成「不限」）与任何内部标识。
 */
@AutoConfigureMockMvc
@DisplayName("S14-1a 商用套餐目录")
class PlanCatalogIntegrationTests extends AbstractIntegrationTest {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** HTTP 调用入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 目录事实与数据库守卫的验证入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 幂等播种用例。 */
    @Autowired
    private PlanCatalogSeedService planCatalogSeedService;
    /** 注册限流器；目录用例注册账号前清理，消除全局状态干扰。 */
    @Autowired
    private AuthRateLimiter rateLimiter;

    /** 四档冻结的 P1/P2 关键值必须逐值落库，并覆盖全部 16 个数值维度。 */
    @Test
    void migrationSeedsFourTiersWithFrozenDimensions() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.code
                  FROM sys_plan p
                  JOIN sys_plan_revision r ON r.plan_id = p.id
                 WHERE r.revision_code = 'product-revision-1'
                 ORDER BY p.display_order
                """, String.class))
                .containsExactly("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL");

        Map<String, List<PlanDimension>> actual = dimensionsByPlan();
        for (PlanDefinition definition : ProductRevision1.definitions()) {
            assertThat(actual.get(definition.code()))
                    .as(definition.code() + " 维度必须与冻结定义逐值一致")
                    .containsExactlyInAnyOrderElementsOf(definition.dimensions());
            assertThat(actual.get(definition.code())).hasSize(16);
        }

        // 冻结正文的标题值再独立核对一次，避免「定义与库同时改错」互相掩护。
        assertThat(dimensionValue("FREE", "PROJECTS_MAX")).isEqualTo(1);
        assertThat(dimensionValue("STANDARD", "PROJECTS_MAX")).isEqualTo(5);
        assertThat(dimensionValue("ENTERPRISE", "PROJECTS_MAX")).isEqualTo(10);
        assertThat(dimensionValue("PROFESSIONAL", "PROJECTS_MAX")).isEqualTo(20);
        assertThat(dimensionValue("FREE", "DEVICES_MAX")).isEqualTo(3);
        assertThat(dimensionValue("STANDARD", "DEVICES_MAX")).isEqualTo(100);
        assertThat(dimensionValue("ENTERPRISE", "DEVICES_MAX")).isEqualTo(300);
        assertThat(dimensionValue("PROFESSIONAL", "DEVICES_MAX")).isEqualTo(1000);
        assertThat(dimensionValue("FREE", "UPLINK_MESSAGE_DAILY")).isEqualTo(700);
        assertThat(dimensionValue("FREE", "DOWNLINK_MESSAGE_DAILY")).isEqualTo(300);
        assertThat(dimensionValue("STANDARD", "UPLINK_MESSAGE_DAILY")).isEqualTo(105000);
        assertThat(dimensionValue("STANDARD", "DOWNLINK_MESSAGE_DAILY")).isEqualTo(45000);
        assertThat(dimensionValue("ENTERPRISE", "UPLINK_MESSAGE_DAILY")).isEqualTo(315000);
        assertThat(dimensionValue("ENTERPRISE", "DOWNLINK_MESSAGE_DAILY")).isEqualTo(135000);
        assertThat(dimensionValue("PROFESSIONAL", "UPLINK_MESSAGE_DAILY")).isEqualTo(1050000);
        assertThat(dimensionValue("PROFESSIONAL", "DOWNLINK_MESSAGE_DAILY")).isEqualTo(450000);
        assertThat(dimensionValue("FREE", "EXTERNAL_COLLABORATOR_SEATS")).isZero();
        assertThat(dimensionValue("STANDARD", "EXTERNAL_COLLABORATOR_SEATS")).isEqualTo(5);
        assertThat(dimensionValue("ENTERPRISE", "EXTERNAL_COLLABORATOR_SEATS")).isEqualTo(15);
        assertThat(dimensionValue("PROFESSIONAL", "EXTERNAL_COLLABORATOR_SEATS")).isEqualTo(50);
        assertThat(dimensionUnit("FREE", "HISTORY_WINDOW")).isEqualTo("DAY");
        assertThat(dimensionValue("FREE", "HISTORY_WINDOW")).isEqualTo(7);
        assertThat(dimensionUnit("STANDARD", "HISTORY_WINDOW")).isEqualTo("MONTH");
        assertThat(dimensionValue("STANDARD", "HISTORY_WINDOW")).isEqualTo(6);
        assertThat(dimensionValue("ENTERPRISE", "HISTORY_WINDOW")).isEqualTo(9);
        assertThat(dimensionValue("PROFESSIONAL", "HISTORY_WINDOW")).isEqualTo(12);
    }

    /** 未定价付费档一律 NOT_FOR_SALE 且没有价格；FREE 是真实的 0 元在售档。 */
    @Test
    void paidTiersRemainNotForSaleWithoutInventedPrice() {
        assertThat(jdbcTemplate.query("""
                SELECT p.code, r.sale_status, r.price_cents, r.billing_period
                  FROM sys_plan p
                  JOIN sys_plan_revision r ON r.plan_id = p.id
                 WHERE r.revision_code = 'product-revision-1'
                 ORDER BY p.display_order
                """, (resultSet, rowNumber) -> new String[] {
                        resultSet.getString("code"),
                        resultSet.getString("sale_status"),
                        String.valueOf(resultSet.getObject("price_cents", Long.class)),
                        resultSet.getString("billing_period")}))
                .containsExactly(
                        new String[] {"FREE", "ON_SALE", "0", "NONE"},
                        new String[] {"STANDARD", "NOT_FOR_SALE", "null", "YEAR"},
                        new String[] {"ENTERPRISE", "NOT_FOR_SALE", "null", "YEAR"},
                        new String[] {"PROFESSIONAL", "NOT_FOR_SALE", "null", "YEAR"});
    }

    /** 未交付能力必须显式 DISABLED，且不占用任何数值维度（与额度 0 语义分离）。 */
    @Test
    void undeliveredCapabilitiesAreDisabledAndCarryNoQuotaRow() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.code || '|' || e.capability_code || '|' || e.state
                  FROM sys_plan_entitlement e
                  JOIN sys_plan_revision r ON r.id = e.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE e.state = 'DISABLED'
                 ORDER BY p.display_order, e.capability_code
                """, String.class))
                .hasSize(16)
                .allSatisfy(row -> assertThat(row).endsWith("|DISABLED"))
                .allSatisfy(row -> assertThat(row).containsAnyOf(
                        "|SMS_CHANNEL|", "|OTA|", "|OPEN_API|", "|APP_SUBSCRIPTION|"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.code || '|' || e.capability_code
                  FROM sys_plan_entitlement e
                  JOIN sys_plan_revision r ON r.id = e.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE e.state = 'ENABLED'
                 ORDER BY p.display_order, e.capability_code
                """, String.class))
                .hasSize(32)
                .allSatisfy(row -> assertThat(row).doesNotContain(
                        "SMS_CHANNEL", "OTA", "OPEN_API", "APP_SUBSCRIPTION"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT capability_code FROM sys_plan_entitlement WHERE capability_code IN
                    ('SMS_CHANNEL', 'OTA', 'OPEN_API', 'APP_SUBSCRIPTION')
                 INTERSECT
                SELECT dimension_code FROM sys_plan_revision_dimension
                """, String.class)).isEmpty();
    }

    /** 数据库入口拒绝未开售价格、用 NULL/0 表示未知额度以及未知权益状态。 */
    @Test
    void databaseRejectsUnknownQuotaValuesAndPriceOnNotForSaleRevision() {
        UUID standardPlanId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_plan WHERE code = 'STANDARD'", UUID.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents, valid_from, valid_until)
                VALUES (?, ?, ?, 99, '探针未开售版', 'NOT_FOR_SALE', 'YEAR', 'CNY', 198000, now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-not-for-sale-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents, valid_from, valid_until)
                VALUES (?, ?, ?, 98, '探针缺价版', 'ON_SALE', 'YEAR', 'CNY', NULL, now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-on-sale-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID freeRevisionId = revisionId("FREE");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision_dimension
                    (plan_revision_id, dimension_code, value_amount, unit, window_kind)
                VALUES (?, 'PROJECTS_MAX', 0, 'COUNT', 'NONE')
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision_dimension
                    (plan_revision_id, dimension_code, value_amount, unit, window_kind)
                VALUES (?, 'NOTIFICATION_DELIVERY_DAILY', NULL, 'DELIVERY', 'UTC_DAY')
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_entitlement (plan_revision_id, capability_code, state)
                VALUES (?, 'SMS_CHANNEL', 'ENABLED_MAYBE')
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 只有冻结正文明确写 0 的去重协作者席位允许 0；探针插入失败后不留任何行。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_plan_revision_dimension
                 WHERE plan_revision_id = ? AND dimension_code = 'PROJECTS_MAX'
                """, Integer.class, freeRevisionId)).isEqualTo(1);
    }

    /** 已发布修订版被引用后，UPDATE/DELETE 必须由数据库守卫拒绝，且值保持不变。 */
    @Test
    void immutableGuardRejectsUpdateAndDeleteOfReferencedRevision() {
        UUID freeRevisionId = revisionId("FREE");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET name = '改名' WHERE id = ?", freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET price_cents = 1 WHERE id = ?", freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM sys_plan_revision WHERE id = ?", freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE sys_plan_revision_dimension SET value_amount = 9999
                 WHERE plan_revision_id = ? AND dimension_code = 'PROJECTS_MAX'
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM sys_plan_revision_dimension
                 WHERE plan_revision_id = ? AND dimension_code = 'PROJECTS_MAX'
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE sys_plan_entitlement SET state = 'ENABLED'
                 WHERE plan_revision_id = ? AND capability_code = 'OTA'
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(dimensionValue("FREE", "PROJECTS_MAX")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM sys_plan_entitlement
                 WHERE plan_revision_id = ? AND capability_code = 'OTA'
                """, String.class, freeRevisionId)).isEqualTo("DISABLED");
    }

    /** 重复播种既不改值也不新增行；播种后的漂移校验基准保持不变。 */
    @Test
    void reseedingProductRevision1IsIdempotentAndNeverChangesValues() {
        String before = catalogSnapshot();
        assertThat(planCatalogSeedService.ensureProductRevision1()).isZero();
        assertThat(planCatalogSeedService.ensureProductRevision1()).isZero();
        assertThat(catalogSnapshot()).isEqualTo(before);
    }

    /** 平台目录端点走现有认证守卫，返回四档公开报价且 JSON 中没有任何 null。 */
    @Test
    void catalogEndpointRequiresAuthenticationAndReturnsFrozenCatalog() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/plans")).andReturn().getResponse().getStatus()).isEqualTo(401);

        rateLimiter.clear();
        String email = "s14-plan-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        String accessToken = JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();

        MvcResult result = mockMvc.perform(get("/api/v1/plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(4);
        for (JsonNode plan : body) for (JsonNode capability : plan.get("entitlements")) {
            assertThat(capability.get("enforcement").asString()).isEqualTo("CATALOG_ONLY");
        }
        assertThat(body.toString()).as("目录响应不得用 null 表达未知或不限").doesNotContain("null");
        assertThat(planCodes(body)).containsExactly("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL");
        assertThat(revisions(body)).containsOnly("product-revision-1");

        JsonNode free = entry(body, "FREE");
        assertThat(free.get("saleStatus").asString()).isEqualTo("ON_SALE");
        assertThat(free.get("priceCents").asLong()).isZero();
        assertThat(dimension(free, "PROJECTS_MAX").get("value").asLong()).isEqualTo(1);
        assertThat(dimension(free, "UPLINK_MESSAGE_DAILY").get("value").asLong()).isEqualTo(700);
        assertThat(dimension(free, "HISTORY_WINDOW").get("unit").asString()).isEqualTo("DAY");

        JsonNode professional = entry(body, "PROFESSIONAL");
        assertThat(professional.get("saleStatus").asString()).isEqualTo("NOT_FOR_SALE");
        assertThat(professional.has("priceCents")).as("未定价档位不得返回价格字段").isFalse();
        assertThat(dimension(professional, "DOWNLINK_MESSAGE_DAILY").get("value").asLong()).isEqualTo(450000);
        assertThat(entitlement(professional, "OTA").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(professional, "SMS_CHANNEL").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(professional, "OPEN_API").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(professional, "APP_SUBSCRIPTION").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(professional, "OBJECT_STORAGE").get("enabled").asBoolean()).isTrue();
        assertThat(professional.get("quotaDimensions").size()).isEqualTo(16);
        assertThat(professional.get("entitlements").size()).isEqualTo(12);
    }

    /**
     * 按数组顺序取出四档编码。
     *
     * @param body 目录数组
     * @return 档位编码列表
     */
    private static List<String> planCodes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.forEach(node -> codes.add(node.get("code").asString()));
        return codes;
    }

    /**
     * 取出各档位的修订版标识。
     *
     * @param body 目录数组
     * @return 修订版标识列表
     */
    private static List<String> revisions(JsonNode body) {
        List<String> revisions = new ArrayList<>();
        body.forEach(node -> revisions.add(node.get("revision").asString()));
        return revisions;
    }

    /**
     * 全量目录行的稳定快照，用于证明重复播种不改值。
     *
     * @return 按套餐排序的目录行文本
     */
    private String catalogSnapshot() {
        List<String> lines = new ArrayList<>();
        lines.addAll(jdbcTemplate.queryForList(
                "SELECT code || '|' || display_order FROM sys_plan ORDER BY code", String.class));
        lines.addAll(jdbcTemplate.queryForList("""
                SELECT p.code || '|' || r.revision_code || '|' || r.revision_no || '|' || r.name || '|'
                       || r.sale_status || '|' || r.billing_period || '|' || r.currency || '|'
                       || coalesce(r.price_cents::text, 'no-price') || '|' || r.valid_from
                  FROM sys_plan p JOIN sys_plan_revision r ON r.plan_id = p.id
                 ORDER BY p.code
                """, String.class));
        lines.addAll(jdbcTemplate.queryForList("""
                SELECT p.code || '|' || d.dimension_code || '|' || d.value_amount || '|' || d.unit
                       || '|' || d.window_kind
                  FROM sys_plan_revision_dimension d
                  JOIN sys_plan_revision r ON r.id = d.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 ORDER BY p.code, d.dimension_code
                """, String.class));
        lines.addAll(jdbcTemplate.queryForList("""
                SELECT p.code || '|' || e.capability_code || '|' || e.state
                  FROM sys_plan_entitlement e
                  JOIN sys_plan_revision r ON r.id = e.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 ORDER BY p.code, e.capability_code
                """, String.class));
        return String.join("\n", lines);
    }

    /**
     * 读取全部套餐的维度并按套餐编码分组。
     *
     * @return 套餐编码到维度列表的映射
     */
    private Map<String, List<PlanDimension>> dimensionsByPlan() {
        return jdbcTemplate.query("""
                SELECT p.code AS plan_code, d.dimension_code, d.value_amount, d.unit, d.window_kind
                  FROM sys_plan_revision_dimension d
                  JOIN sys_plan_revision r ON r.id = d.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE r.revision_code = 'product-revision-1'
                 ORDER BY p.display_order, d.dimension_code
                """, resultSet -> {
                    Map<String, List<PlanDimension>> grouped = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        grouped.computeIfAbsent(resultSet.getString("plan_code"), key -> new ArrayList<>())
                                .add(new PlanDimension(resultSet.getString("dimension_code"),
                                        resultSet.getLong("value_amount"), resultSet.getString("unit"),
                                        resultSet.getString("window_kind")));
                    }
                    return grouped;
                });
    }

    /** @param code 套餐编码 @param dimensionCode 维度编码 @return 数据库中的冻结数值 */
    private long dimensionValue(String code, String dimensionCode) {
        return jdbcTemplate.queryForObject("""
                SELECT d.value_amount
                  FROM sys_plan_revision_dimension d
                  JOIN sys_plan_revision r ON r.id = d.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1' AND d.dimension_code = ?
                """, Long.class, code, dimensionCode);
    }

    /** @param code 套餐编码 @param dimensionCode 维度编码 @return 数据库中的维度单位 */
    private String dimensionUnit(String code, String dimensionCode) {
        return jdbcTemplate.queryForObject("""
                SELECT d.unit
                  FROM sys_plan_revision_dimension d
                  JOIN sys_plan_revision r ON r.id = d.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1' AND d.dimension_code = ?
                """, String.class, code, dimensionCode);
    }

    /** @param code 套餐编码 @return 该套餐 product-revision-1 的修订版 ID */
    private UUID revisionId(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT r.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = 'product-revision-1'
                """, UUID.class, code);
    }

    /** @param body 目录数组 @param code 套餐编码 @return 该档响应 @throws AssertionError 缺失该档位 */
    private static JsonNode entry(JsonNode body, String code) {
        for (JsonNode node : body) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("目录缺少档位 " + code);
    }

    /** @param entry 档位响应 @param code 维度编码 @return 维度响应 */
    private static JsonNode dimension(JsonNode entry, String code) {
        for (JsonNode node : entry.get("quotaDimensions")) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("档位 " + entry.get("code").asString() + " 缺少维度 " + code);
    }

    /** @param entry 档位响应 @param code capability code @return 权益响应 */
    private static JsonNode entitlement(JsonNode entry, String code) {
        for (JsonNode node : entry.get("entitlements")) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("档位 " + entry.get("code").asString() + " 缺少权益 " + code);
    }
}
