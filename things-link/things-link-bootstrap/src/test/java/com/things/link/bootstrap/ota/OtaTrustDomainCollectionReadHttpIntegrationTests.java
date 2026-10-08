package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.api.OtaTrustDomainSummaryResponse;
import com.things.link.ota.api.OtaTrustKeyResponse;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4e-8：OTA信任域集合与逐键公开元数据只读端点（D-152的③）。
 *
 * <p>真实PG、真实JWT与完整HTTP往返：信任域与发布键事实<b>只经真实导入入口</b>
 * {@code POST .../ota/trust-domains/{domain}/bundles}产生——包与签名由测试内存中的离线根
 * 独立JCA生成，再由生产{@code OtaTrustService.importBundle}完成根验真、字段闭集校验、
 * 状态转移守卫、修订CAS与同事务审计。没有任何测试拼行，也没有绕过根签名的旁路。
 *
 * <p>本片的核心安全断言：两个端点走{@code ota:read}（任意项目成员可读），因此响应必须
 * <b>只</b>暴露信任域公开摘要与逐键公开元数据；私钥材料、SPKI、规范包字节、签名、
 * {@code policyHash}、租户/项目标识一律不得出现，且本片<b>不新增任何密钥状态变更端点</b>
 * ——POST到两个只读路由必须得到405，状态变更留在独立安全切片。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaTrustDomainCollectionReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 离线根与三个发布键只属于本测试，私钥不离开测试内存。 */
    private static final KeyPair ROOT = pair(), FIRST = pair(), SECOND = pair(), THIRD = pair();
    /** 启动配置必须预先知道固定测试scope，不由HTTP输入改变。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate());
    /** 另一个已配置项目：其域对CONFIGURED成员不可见，用于跨项目404。 */
    private static final Fixture OTHER = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate());
    /** 本项目四个独占域名；小写十六进制保证Java与PG排序完全一致。 */
    private static final List<String> OWN_DOMAINS = List.of(domain(), domain(), domain(), domain());
    /** 外项目独占域名。 */
    private static final String OTHER_DOMAIN = domain();
    /** 所有签名都来自真实规范字节。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 只读取真实HTTP响应。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 成员可读响应里出现任何一个字段名都算泄露。 */
    private static final List<String> FORBIDDEN_FIELDS = List.of("\"spki\"", "\"canonicalBundle\"",
            "\"signature\"", "\"tenantId\"", "\"projectId\"", "\"policyHash\"", "\"rootSpki\"",
            "\"privateKey\"", "\"secret\"", "\"token\"");
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 启动前把离线根与两个项目的授权域固定到测试scope，请求正文无法替换根。 */
    @DynamicPropertySource
    static void anchors(DynamicPropertyRegistry registry) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String domain : OWN_DOMAINS) entries.add(anchor(CONFIGURED, domain));
        entries.add(anchor(OTHER, OTHER_DOMAIN));
        registry.add("things-link.ota.trust.anchors-json",
                () -> new String(CANONICAL.writeObject(Map.of("anchors", entries)), StandardCharsets.UTF_8));
    }

    /** 域集合按域名升序分页，跨页不重不漏，并只统计本项目域；ACTIVE键摘要随真实轮换更新。 */
    @Test void listsOwnedDomainsInNameOrderWithActiveKeyAndPaginates() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(OTHER);
        importBundle(other, OTHER_DOMAIN, "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        for (String domain : OWN_DOMAINS) {
            importBundle(own, domain, "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        }
        // 真实轮换路径：第二版包把first降为VERIFY_ONLY并激活second，投影必须反映当前包而不是首包。
        importBundle(own, OWN_DOMAINS.get(0), "1", 2,
                List.of(key(FIRST, "first", "VERIFY_ONLY"), key(SECOND, "second", "ACTIVE")));

        List<String> expected = new ArrayList<>(OWN_DOMAINS);
        Collections.sort(expected);
        JsonNode first = ok(send(own, "GET", collection(own) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(domains(first)).containsExactly(expected.get(0), expected.get(1)).doesNotContain(OTHER_DOMAIN);

        JsonNode second = ok(send(own, "GET",
                collection(own) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(domains(second)).containsExactly(expected.get(2), expected.get(3))
                .doesNotContainAnyElementsOf(domains(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();

        JsonNode all = ok(send(own, "GET", collection(own) + "?limit=100"), 200);
        assertThat(domains(all)).containsExactlyElementsOf(expected);
        JsonNode rotated = item(all, OWN_DOMAINS.get(0));
        assertThat(rotated.path("bundleVersion").asText()).isEqualTo("2");
        assertThat(rotated.path("activeKeyVersion").asText()).isEqualTo("second");
        assertThat(rotated.path("activeKeyFingerprint").asText()).isEqualTo(sha(SECOND.getPublic().getEncoded()));
        assertThat(rotated.path("bundleSha256").asText())
                .isEqualTo(sha(canonical(OWN_DOMAINS.get(0), 2,
                        List.of(key(FIRST, "first", "VERIFY_ONLY"), key(SECOND, "second", "ACTIVE")))));
        JsonNode untouched = item(all, expected.stream()
                .filter(name -> !name.equals(OWN_DOMAINS.get(0))).findFirst().orElseThrow());
        assertThat(untouched.path("activeKeyVersion").asText()).isEqualTo("first");
        assertThat(untouched.path("activeKeyFingerprint").asText()).isEqualTo(sha(FIRST.getPublic().getEncoded()));
    }

    /** 当前包的逐键公开元数据按keyVersion升序分页，跨页不重不漏，末页无游标。 */
    @Test void listsCurrentBundleKeysInVersionOrderAndPaginates() throws Exception {
        Fixture own = seed(CONFIGURED);
        String domain = OWN_DOMAINS.get(0);
        importBundle(own, domain, "0", 1, List.of(key(FIRST, "k1", "PREPARED"),
                key(SECOND, "k2", "ACTIVE"), key(THIRD, "k3", "VERIFY_ONLY")));

        JsonNode first = ok(send(own, "GET", keys(own, domain) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(keyVersions(first)).containsExactly("k1", "k2");
        assertThat(first.path("items").get(0).path("state").asText()).isEqualTo("PREPARED");
        assertThat(first.path("items").get(1).path("state").asText()).isEqualTo("ACTIVE");
        assertThat(first.path("items").get(1).path("signatureProfile").asText()).isEqualTo("TC_OTA_ED25519_V1");
        assertThat(first.path("items").get(1).path("fingerprint").asText())
                .isEqualTo(sha(SECOND.getPublic().getEncoded()));
        assertThat(first.path("items").get(1).path("notBefore").asLong()).isZero();
        assertThat(first.path("items").get(1).path("notAfter").asLong()).isEqualTo(253402300799L);

        JsonNode second = ok(send(own, "GET",
                keys(own, domain) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(keyVersions(second)).containsExactly("k3").doesNotContainAnyElementsOf(keyVersions(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();

        // 已消费到末键的合法游标返回200空页，而不是错误或重复上一页。
        JsonNode empty = ok(send(own, "GET", keys(own, domain) + "?cursor="
                + Cursor.encode(own.project() + "|" + domain + "|1|k3")), 200);
        assertThat(empty.path("items").size()).isZero();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
    }

    /** 两个端点都只有白名单字段；显式断言白名单，并断言响应与路由都没有秘密或状态变更面。 */
    @Test void exposesOnlyWhitelistedPublicMetadataAndNoSecretsOrMutationRoutes() throws Exception {
        Fixture own = seed(CONFIGURED);
        String domain = OWN_DOMAINS.get(0);
        importBundle(own, domain, "0", 1,
                List.of(key(FIRST, "first", "VERIFY_ONLY"), key(SECOND, "second", "ACTIVE")));

        String domainsBody = send(own, "GET", collection(own)).body();
        JsonNode domainsPage = JSON.readTree(domainsBody);
        assertThat(domainsPage.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("trustDomain",
                "revision", "bundleVersion", "policyRevision", "rootProfile", "rootFingerprint", "bundleSha256",
                "activeKeyVersion", "activeKeyFingerprint", "createdAt", "updatedAt");
        assertThat(domainsPage.path("items").get(0).path("activeKeyVersion").asText()).isEqualTo("second");

        String keysBody = send(own, "GET", keys(own, domain)).body();
        JsonNode keysPage = JSON.readTree(keysBody);
        assertThat(keysPage.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("keyVersion", "state",
                "signatureProfile", "fingerprint", "notBefore", "notAfter");
        for (String field : FORBIDDEN_FIELDS) {
            assertThat(domainsBody).as("域摘要不得含 %s", field).doesNotContain(field);
            assertThat(keysBody).as("密钥公开元数据不得含 %s", field).doesNotContain(field);
        }
        // 白名单同时钉在DTO上：将来新增字段必须先改这条断言，不能悄悄放宽成员可见面。
        assertThat(OtaTrustDomainSummaryResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("trustDomain", "revision", "bundleVersion", "policyRevision", "rootProfile",
                        "rootFingerprint", "bundleSha256", "activeKeyVersion", "activeKeyFingerprint",
                        "createdAt", "updatedAt");
        assertThat(OtaTrustKeyResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("keyVersion", "state", "signatureProfile", "fingerprint", "notBefore", "notAfter");
        // 冻结合同的另一半：本片只读，不新增rotate/retire/import状态变更端点。
        // 直接核对生成的OpenAPI路由方法闭集，而不是靠"POST恰好失败"，避免平台兜底把405变成5xx时
        // 让断言失去意义；同时确认相邻的受控导入端点仍然存在且仍是POST。
        JsonNode spec = JSON.readTree(send(own, "GET", "/v3/api-docs").body());
        JsonNode collectionRoute = spec.path("paths")
                .path("/api/v1/projects/{projectId}/ota/trust-domains");
        assertThat(collectionRoute.propertyNames()).containsExactly("get");
        assertThat(collectionRoute.path("get").path("operationId").asString()).isEqualTo("listOtaTrustDomains");
        JsonNode keysRoute = spec.path("paths")
                .path("/api/v1/projects/{projectId}/ota/trust-domains/{trustDomain}/keys");
        assertThat(keysRoute.propertyNames()).containsExactly("get");
        assertThat(keysRoute.path("get").path("operationId").asString()).isEqualTo("listOtaTrustKeys");
        assertThat(spec.path("paths")
                .path("/api/v1/projects/{projectId}/ota/trust-domains/{trustDomain}/bundles")
                .propertyNames()).containsExactly("post");
    }

    /** 项目真实存在但从未登记信任域时是200空页，而不是404。 */
    @Test void returnsEmptyCollectionForProjectWithoutTrustDomains() throws Exception {
        Fixture own = seed(CONFIGURED);
        JsonNode page = ok(send(own, "GET", collection(own)), 200);
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    /** 只准备项目归档状态夹具，域及键仍来自真实导入；只读不能被写额度守卫拒绝。 */
    @Test void archivedProjectRetainsPublicDomainAndKeyReads() throws Exception {
        Fixture own = seed(CONFIGURED);
        importBundle(own, OWN_DOMAINS.get(0), "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", own.project());
        assertThat(domains(ok(send(own, "GET", collection(own)), 200))).containsExactly(OWN_DOMAINS.get(0));
        assertThat(keyVersions(ok(send(own, "GET", keys(own, OWN_DOMAINS.get(0))), 200))).containsExactly("first");
    }

    /** 未知域、外项目域与不可见项目一律按OTA域404/70013隐藏存在性；非法域名是400。 */
    @Test void hidesUnknownForeignDomainAndForeignProjectAsNotFound() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(OTHER);
        importBundle(own, OWN_DOMAINS.get(0), "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        importBundle(other, OTHER_DOMAIN, "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        error(send(own, "GET", keys(own, Uuid7.generate().toString())), 404, 70013);
        error(send(own, "GET", keys(own, OTHER_DOMAIN)), 404, 70013);
        error(send(other, "GET", keys(other, OWN_DOMAINS.get(0))), 404, 70013);
        // 域名字符集在进入仓储前就被拒绝，不落成404探测面。
        error(send(own, "GET", keys(own, "-bad")), 400, 10001);
    }

    /** 未认证401；不属于该项目的调用方（因而没有ota:read）按项目不存在404/50001拒绝；成员同权。 */
    @Test void enforcesAuthenticationAndReadScope() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(OTHER);
        importBundle(own, OWN_DOMAINS.get(0), "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        assertThat(anonymous("GET", collection(own)).statusCode()).isEqualTo(401);
        assertThat(anonymous("GET", keys(own, OWN_DOMAINS.get(0))).statusCode()).isEqualTo(401);
        error(send(other, "GET", collection(own)), 404, 50001);
        error(send(other, "GET", keys(own, OWN_DOMAINS.get(0))), 404, 50001);
        // ota:read是成员制：VIEWER与相邻读端点一样可读，本片不改变该边界。
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        assertThat(domains(ok(send(own, "GET", collection(own)), 200))).containsExactly(OWN_DOMAINS.get(0));
        assertThat(keyVersions(ok(send(own, "GET", keys(own, OWN_DOMAINS.get(0))), 200))).containsExactly("first");
    }

    /** 非法游标、跨项目/跨域/跨包版本游标与越界limit都是400/10001，不泄露其他范围的位置。 */
    @Test void rejectsMalformedCrossParentCursorAndOutOfRangeLimit() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(OTHER);
        String left = OWN_DOMAINS.get(0);
        String right = OWN_DOMAINS.get(1);
        importBundle(own, left, "0", 1, List.of(key(FIRST, "first", "ACTIVE")));
        importBundle(own, right, "0", 1, List.of(key(FIRST, "first", "ACTIVE")));

        error(send(own, "GET", collection(own) + "?cursor=%E8%BF%99%E4%B8%8D%E6%98%AF%E6%B8%B8%E6%A0%87"),
                400, 10001);
        error(send(own, "GET", collection(own) + "?cursor=" + Cursor.encode(own.project().toString())),
                400, 10001);
        error(send(own, "GET", collection(own) + "?cursor="
                + Cursor.encode(other.project() + "|" + OTHER_DOMAIN)), 400, 10001);
        error(send(own, "GET", collection(own) + "?limit=0"), 400, 10001);
        error(send(own, "GET", collection(own) + "?limit=101"), 400, 10001);

        error(send(own, "GET", keys(own, left) + "?cursor=%E8%BF%99%E4%B8%8D%E6%98%AF%E6%B8%B8%E6%A0%87"),
                400, 10001);
        error(send(own, "GET", keys(own, left) + "?cursor="
                + Cursor.encode(own.project() + "|" + right + "|1|first")), 400, 10001);
        error(send(own, "GET", keys(own, left) + "?cursor="
                + Cursor.encode(other.project() + "|" + left + "|1|first")), 400, 10001);
        error(send(own, "GET", keys(own, left) + "?cursor="
                + Cursor.encode(own.project() + "|" + left + "|2|first")), 400, 10001);
        error(send(own, "GET", keys(own, left) + "?cursor="
                + Cursor.encode(own.project() + "|" + left + "|1|absent")), 400, 10001);
        error(send(own, "GET", keys(own, left) + "?limit=0"), 400, 10001);
        error(send(own, "GET", keys(own, left) + "?limit=101"), 400, 10001);
    }

    /** 域集合路径。 */
    private static String collection(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/trust-domains";
    }

    /** 域密钥路径。 */
    private static String keys(Fixture f, String domain) {
        return collection(f) + "/" + domain + "/keys";
    }

    /** 受控导入路径，用于证明只读片没有删除既有写端点。 */
    private static String bundles(Fixture f, String domain) {
        return collection(f) + "/" + domain + "/bundles";
    }

    /** 响应条目域名按响应顺序。 */
    private static List<String> domains(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("trustDomain").asText()).toList();
    }

    /** 响应条目键版本按响应顺序。 */
    private static List<String> keyVersions(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("keyVersion").asText()).toList();
    }

    /** 取指定域名的条目；缺失直接断言失败。 */
    private static JsonNode item(JsonNode page, String domain) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .filter(entry -> domain.equals(entry.path("trustDomain").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("响应缺少信任域 " + domain));
    }

    /** 真实HTTP导入入口：根验真、字段闭集、状态转移与审计全部走生产路径。 */
    private void importBundle(Fixture f, String domain, String revision, long version,
            List<Map<String, Object>> keys) throws Exception {
        byte[] canonical = canonical(domain, version, keys);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ROOT.getPrivate());
        signer.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signer.update(canonical);
        byte[] body = CANONICAL.writeObject(Map.of("expectedRevision", revision,
                "bundle", bundle(domain, version, keys), "signature", b64(signer.sign())));
        HttpResponse<String> response = exchange(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + bundles(f, domain)))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + tokens.issue(
                        new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /** 完整包对象与签名输入完全同源。 */
    private static Map<String, Object> bundle(String domain, long version, List<Map<String, Object>> keys) {
        return Map.of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", domain,
                "bundleVersion", version, "keys", keys);
    }

    /** 真实规范字节；签名与摘要断言都基于同一份字节。 */
    private static byte[] canonical(String domain, long version, List<Map<String, Object>> keys) {
        return CANONICAL.writeObject(bundle(domain, version, keys));
    }

    /** 固定有效区间，后续包不能改写旧key的不可变字段。 */
    private static Map<String, Object> key(KeyPair pair, String version, String state) {
        return Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1",
                "spki", b64(pair.getPublic().getEncoded()), "fingerprint", sha(pair.getPublic().getEncoded()),
                "state", state, "notBefore", 0L, "notAfter", 253402300799L);
    }

    /** 启动配置中的单条根授权，只含公钥与范围。 */
    private static Map<String, Object> anchor(Fixture f, String domain) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tenantId", f.tenant().toString());
        entry.put("projectId", f.project().toString());
        entry.put("trustDomain", domain);
        entry.put("allowedDeviceTypeIds", List.of(f.type().toString()));
        entry.put("rootProfile", "TC_OTA_ED25519_V1");
        entry.put("rootSpki", b64(ROOT.getPublic().getEncoded()));
        entry.put("rootFingerprint", sha(ROOT.getPublic().getEncoded()));
        entry.put("policyRevision", 1L);
        return entry;
    }

    /** 独占域名，只含小写十六进制，Java与PG排序一致。 */
    private static String domain() {
        return "col" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 独立生成临时测试根/发布键。 */
    private static KeyPair pair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** 标准带填充Base64。 */ private static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    /** 独立完整SHA256。 */ private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    /** 每例自有身份图。 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type) { }

    /** 身份骨架；信任域与包必须由真实导入路径产生。 */
    private Fixture seed(Fixture fixture) {
        fixtures.add(fixture);
        JdbcTemplate jdbc = owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)"
                + " VALUES (?,?,'{noop}unused','OTA信任读取测试',now())",
                fixture.account(), fixture.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA信任读取租户')", fixture.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), fixture.tenant(), fixture.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA信任读取项目','sh-1',?)",
                fixture.project(), fixture.tenant(), "otatrust_" + fixture.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.project(), fixture.account());
        return fixture;
    }

    /** 真实HTTP/1.1往返，JWT经过生产验签与数据库角色读取。 */
    private HttpResponse<String> send(Fixture f, String method, String path) throws Exception {
        return exchange(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + tokens.issue(
                        new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value())
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }

    /** 无凭据往返。 */
    private HttpResponse<String> anonymous(String method, String path) throws Exception {
        return exchange(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }

    /** 复用真实HTTP/1.1客户端。 */
    private static HttpResponse<String> exchange(HttpRequest.Builder builder) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 明确HTTP状态并保留响应首因。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** HTTP状态和领域码均须匹配。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }

    /** owner连接只用于夹具准备、最终观察与清理，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 子先父后清理本例全部事实；包必须在域之前删除。 */
    @AfterEach void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.project());
            });
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.project());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.project());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenant());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenant());
            owner.update("DELETE FROM sys_account WHERE id = ?", fixture.account());
        }
        fixtures.clear();
    }
}
