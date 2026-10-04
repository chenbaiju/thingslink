package com.things.link.bootstrap.project.plan;

import com.things.link.iam.application.AuthRateLimiter;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S14-1c 参考价快照与控制面只读端点的真实 PostgreSQL + HTTP 验收。
 *
 * <p>钉住四件事：①四档参考价按 S14-0 §7/§9 逐值落库（0/298000/598000/1198000 分）而销售状态
 * 与成交价不被参考价改写；②参考价永远不能替代成交价——在售但无成交价的修订版连参考价一起
 * 仍被数据库拒绝；③单档详情返回冻结维度与权益，DISABLED 保留且不携带数值额度，响应无
 * {@code null}；④未知编码走标准错误响应 404，未认证 401。
 */
@AutoConfigureMockMvc
@DisplayName("S14-1c 参考价快照与套餐控制面")
class PlanReferencePriceIntegrationTests extends AbstractIntegrationTest {

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试账号固定口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** HTTP 调用入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 目录事实与数据库约束的验证入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 注册限流器；注册账号前清理，消除全局状态干扰。 */
    @Autowired
    private AuthRateLimiter rateLimiter;

    /** 四档参考价与币种必须逐值落库，且销售状态与成交价保持 S14-1a 的结论。 */
    @Test
    void referencePricesAreSeededExactlyWithoutChangingSaleStatus() {
        assertThat(jdbcTemplate.query("""
                SELECT p.code, r.sale_status, r.price_cents,
                       r.reference_price_cents, r.reference_price_currency
                  FROM sys_plan p
                  JOIN sys_plan_revision r ON r.plan_id = p.id
                 WHERE r.revision_code = 'product-revision-1'
                 ORDER BY p.display_order
                """, (resultSet, rowNumber) -> new String[] {
                        resultSet.getString("code"),
                        resultSet.getString("sale_status"),
                        String.valueOf(resultSet.getObject("price_cents", Long.class)),
                        String.valueOf(resultSet.getObject("reference_price_cents", Long.class)),
                        resultSet.getString("reference_price_currency")}))
                .containsExactly(
                        new String[] {"FREE", "ON_SALE", "0", "0", "CNY"},
                        new String[] {"STANDARD", "NOT_FOR_SALE", "null", "298000", "CNY"},
                        new String[] {"ENTERPRISE", "NOT_FOR_SALE", "null", "598000", "CNY"},
                        new String[] {"PROFESSIONAL", "NOT_FOR_SALE", "null", "1198000", "CNY"});
    }

    /** 付费三档仍然没有成交价：参考价不是可售价格。 */
    @Test
    void paidTiersStillHaveNoSalePrice() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.code
                  FROM sys_plan p
                  JOIN sys_plan_revision r ON r.plan_id = p.id
                 WHERE r.revision_code = 'product-revision-1'
                   AND r.price_cents IS NULL
                 ORDER BY p.display_order
                """, String.class)).containsExactly("STANDARD", "ENTERPRISE", "PROFESSIONAL");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM sys_plan_revision r
                 WHERE r.revision_code = 'product-revision-1'
                   AND r.sale_status = 'NOT_FOR_SALE'
                   AND r.price_cents IS NOT NULL
                """, Integer.class)).isZero();
    }

    /** 在售必须有成交价；参考价即使存在也不能替代它，未在售也不得写成交价。 */
    @Test
    void saleableRevisionWithoutSalePriceIsRejectedAndReferencePriceCannotSubstituteIt() {
        UUID standardPlanId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_plan WHERE code = 'STANDARD'", UUID.class);

        // 在售 + 无成交价 + 有参考价：参考价不得被当成成交价，必须仍被拒。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents,
                                               reference_price_cents, reference_price_currency,
                                               valid_from, valid_until)
                VALUES (?, ?, ?, 97, '探针参考价冒充成交价', 'ON_SALE', 'YEAR', 'CNY', NULL,
                        298000, 'CNY', now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-reference-as-sale-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 在售 + 无成交价（也没有参考价）：仍然被拒。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents, valid_from, valid_until)
                VALUES (?, ?, ?, 96, '探针在售缺价', 'ON_SALE', 'YEAR', 'CNY', NULL, now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-on-sale-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 未在售却写成交价：被拒（参考价的存在不放宽该约束）。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents,
                                               reference_price_cents, reference_price_currency,
                                               valid_from, valid_until)
                VALUES (?, ?, ?, 95, '探针未开售有成交价', 'NOT_FOR_SALE', 'YEAR', 'CNY', 198000,
                        298000, 'CNY', now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-not-for-sale-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 参考价币种必须等于修订版唯一币种：不一致直接拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents,
                                               reference_price_cents, reference_price_currency,
                                               valid_from, valid_until)
                VALUES (?, ?, ?, 94, '探针币种漂移', 'ON_SALE', 'YEAR', 'CNY', 0,
                        298000, 'USD', now(), NULL)
                """, Uuid7.generate(), standardPlanId, "probe-currency-drift-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 探针全部失败，目录仍恰为四档，没有任何半截行留下。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_plan_revision WHERE revision_code LIKE 'probe-%'
                """, Integer.class)).isZero();
    }

    /** 目录端点返回参考价、币种与销售状态；付费三档仍无成交价字段。 */
    @Test
    void catalogEndpointExposesReferencePriceCurrencyAndSaleStatus() throws Exception {
        String accessToken = authenticatedToken();
        MvcResult result = mockMvc.perform(get("/api/v1/plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.toString()).as("目录响应不得用 null 表达未知或不限").doesNotContain("null");

        JsonNode free = entry(body, "FREE");
        assertThat(free.get("saleStatus").asString()).isEqualTo("ON_SALE");
        assertThat(free.get("priceCents").asLong()).isZero();
        assertThat(free.get("referencePriceCents").asLong()).isZero();
        assertThat(free.get("referencePriceCurrency").asString()).isEqualTo("CNY");

        JsonNode standard = entry(body, "STANDARD");
        assertThat(standard.get("saleStatus").asString()).isEqualTo("NOT_FOR_SALE");
        assertThat(standard.has("priceCents")).as("未定价档位不得返回成交价字段").isFalse();
        assertThat(standard.get("referencePriceCents").asLong()).isEqualTo(298000);

        JsonNode enterprise = entry(body, "ENTERPRISE");
        assertThat(enterprise.get("referencePriceCents").asLong()).isEqualTo(598000);

        JsonNode professional = entry(body, "PROFESSIONAL");
        assertThat(professional.get("referencePriceCents").asLong()).isEqualTo(1198000);
        assertThat(professional.get("referencePriceCurrency").asString()).isEqualTo("CNY");
    }

    /** 参考价一经补齐即不可原地修改或清空：调价必须新建修订版，历史行的参考价不被重解释。 */
    @Test
    void referencePriceIsImmutableOnceSeeded() {
        UUID freeRevisionId = jdbcTemplate.queryForObject("""
                SELECT r.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = 'FREE' AND r.revision_code = 'product-revision-1'
                """, UUID.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET reference_price_cents = reference_price_cents + 1 WHERE id = ?",
                freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET reference_price_cents = NULL, reference_price_currency = NULL WHERE id = ?",
                freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reference_price_cents FROM sys_plan_revision WHERE id = ?", Long.class, freeRevisionId))
                .isZero();
    }

    /** 单档详情返回冻结维度与权益；DISABLED 保留且不携带数值额度，响应无 null。 */
    @Test
    void planDetailReturnsFrozenDimensionsAndEntitlementsWithoutNull() throws Exception {
        String accessToken = authenticatedToken();
        MvcResult result = mockMvc.perform(get("/api/v1/plans/PROFESSIONAL")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String raw = result.getResponse().getContentAsString();
        assertThat(raw).as("详情响应不得用 null 表达未知或不限").doesNotContain("null");
        JsonNode body = JSON.readTree(raw);

        assertThat(body.get("code").asString()).isEqualTo("PROFESSIONAL");
        assertThat(body.get("revision").asString()).isEqualTo("product-revision-1");
        assertThat(body.get("saleStatus").asString()).isEqualTo("NOT_FOR_SALE");
        assertThat(body.has("priceCents")).isFalse();
        assertThat(body.get("referencePriceCents").asLong()).isEqualTo(1198000);
        assertThat(body.get("quotaDimensions").size()).isEqualTo(16);
        assertThat(body.get("entitlements").size()).isEqualTo(12);
        assertThat(dimensionCode(body, "PROJECTS_MAX")).isEqualTo(20);
        assertThat(dimensionCode(body, "DEVICES_MAX")).isEqualTo(1000);
        assertThat(dimensionUnit(body, "HISTORY_WINDOW")).isEqualTo("MONTH");
        assertThat(entitlement(body, "OTA").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(body, "SMS_CHANNEL").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(body, "OPEN_API").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(body, "APP_SUBSCRIPTION").get("enabled").asBoolean()).isFalse();
        assertThat(entitlement(body, "OBJECT_STORAGE").get("enabled").asBoolean()).isTrue();

        // DISABLED 权益不携带任何数值额度：权益码不得出现在维度码里，权益节点也没有数值字段。
        List<String> dimensionCodes = new ArrayList<>();
        body.get("quotaDimensions").forEach(node -> dimensionCodes.add(node.get("code").asString()));
        for (String capability : List.of("SMS_CHANNEL", "OTA", "OPEN_API", "APP_SUBSCRIPTION")) {
            assertThat(dimensionCodes).doesNotContain(capability);
            JsonNode disabled = entitlement(body, capability);
            assertThat(disabled.has("value")).isFalse();
            assertThat(disabled.has("quota")).isFalse();
        }
    }

    /** 未知编码（形状合法与形状非法）都返回 404 与标准错误响应，而不是 200 空对象。 */
    @Test
    void unknownPlanCodeReturnsStandardNotFoundError() throws Exception {
        String accessToken = authenticatedToken();
        for (String unknown : List.of("LEGACY", "free", "not-a-plan")) {
            MvcResult result = mockMvc.perform(get("/api/v1/plans/" + unknown)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn();
            assertThat(result.getResponse().getStatus()).as("未知编码 " + unknown).isEqualTo(404);
            JsonNode error = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(error.get("code").asInt()).as("资源不存在错误码").isEqualTo(10004);
            assertThat(error.get("message").asString()).isNotBlank();
            assertThat(error.get("traceId").asString()).isNotBlank();
            assertThat(error.get("details").isArray()).isTrue();
        }
    }

    /** 列表与详情都走现有认证守卫：无令牌一律 401。 */
    @Test
    void catalogAndDetailRequireAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/plans")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/plans/FREE")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    /**
     * 注册并登录一个已验证账号，返回可用访问令牌。
     *
     * @return 访问令牌
     * @throws Exception 注册或登录请求失败
     */
    private String authenticatedToken() throws Exception {
        rateLimiter.clear();
        String email = "s14-reference-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
    }

    /**
     * 取出目录数组中指定编码的档位。
     *
     * @param body 目录数组
     * @param code 套餐编码
     * @return 该档位响应
     */
    private static JsonNode entry(JsonNode body, String code) {
        for (JsonNode node : body) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("目录缺少档位 " + code);
    }

    /**
     * 读取详情响应中某维度的数值。
     *
     * @param body 档位详情
     * @param code 维度编码
     * @return 冻结数值
     */
    private static long dimensionCode(JsonNode body, String code) {
        return dimension(body, code).get("value").asLong();
    }

    /**
     * 读取详情响应中某维度的单位。
     *
     * @param body 档位详情
     * @param code 维度编码
     * @return 单位
     */
    private static String dimensionUnit(JsonNode body, String code) {
        return dimension(body, code).get("unit").asString();
    }

    /**
     * 取出详情响应中的某个维度节点。
     *
     * @param body 档位详情
     * @param code 维度编码
     * @return 维度节点
     */
    private static JsonNode dimension(JsonNode body, String code) {
        for (JsonNode node : body.get("quotaDimensions")) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("档位缺少维度 " + code);
    }

    /**
     * 取出详情响应中的某个权益节点。
     *
     * @param body 档位详情
     * @param code capability code
     * @return 权益节点
     */
    private static JsonNode entitlement(JsonNode body, String code) {
        for (JsonNode node : body.get("entitlements")) {
            if (code.equals(node.get("code").asString())) {
                return node;
            }
        }
        throw new AssertionError("档位缺少权益 " + code);
    }
}
