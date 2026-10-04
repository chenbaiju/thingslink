package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4e-5：固件维度上传会话与发布尝试只读集合端点。
 *
 * <p>真实PG、真实JWT与完整HTTP往返，不触达对象存储：读取投影只消费已持久的历史事实。
 * 这里显式覆盖「空页不是404」与「父固件不存在/跨项目一律404」这两条容易混淆的边界，
 * 以及游标跨页不重不漏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaFirmwareCollectionReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 上传会话历史按创建时刻倒序，跨页不重不漏，且只返回本项目本固件的事实。 */
    @Test void listsUploadsNewestFirstAndPaginatesAcrossMoreThanOnePage() throws Exception {
        Fixture own = seed(), other = seed();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
        UUID oldest = cleanedUpload(own, base).id();
        UUID middle = cleanedUpload(own, base.plusSeconds(60)).id();
        UUID newest = cleanedUpload(own, base.plusSeconds(120)).id();
        cleanedUpload(other, base.plusSeconds(180));

        JsonNode first = ok(send(own, "GET", uploads(own) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(ids(first)).containsExactly(newest, middle);
        assertThat(first.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("id", "projectId",
                "firmwareId", "status", "revision", "expectedLength", "expectedSha256", "failureCode",
                "createdAt", "expiresAt", "cancelRequestedAt", "cleanupCompletedAt");
        assertThat(first.path("items").get(0).path("revision").asText()).isEqualTo("2");
        assertThat(first.path("items").get(0).path("expectedLength").asLong()).isEqualTo(1024);

        JsonNode second = ok(send(own, "GET",
                uploads(own) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(ids(second)).containsExactly(oldest).doesNotContainAnyElementsOf(ids(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    /** 发布尝试历史同样倒序分页，且不泄露清单、签名与内部租约字段。 */
    @Test void listsPublicationsNewestFirstAndPaginatesAcrossMoreThanOnePage() throws Exception {
        Fixture own = seed(), other = seed();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
        UUID oldest = rejectedPublication(own, cleanedUpload(own, base), base).id();
        UUID middle = rejectedPublication(own, cleanedUpload(own, base.plusSeconds(60)), base.plusSeconds(60)).id();
        UUID newest = rejectedPublication(own, cleanedUpload(own, base.plusSeconds(120)), base.plusSeconds(120)).id();
        rejectedPublication(other, cleanedUpload(other, base.plusSeconds(180)), base.plusSeconds(180));

        HttpResponse<String> response = send(own, "GET", publications(own) + "?limit=2");
        assertThat(response.body()).doesNotContain("canonicalManifest").doesNotContain("trustSnapshot")
                .doesNotContain("spki").doesNotContain("signature").doesNotContain("receipt")
                .doesNotContain("leaseToken").doesNotContain("objectKey");
        JsonNode first = ok(response, 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(ids(first)).containsExactly(newest, middle);
        assertThat(first.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("id", "firmwareId",
                "uploadSessionId", "status", "revision", "failureCode", "createdAt", "updatedAt");
        assertThat(first.path("items").get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(first.path("items").get(0).path("revision").asText()).isEqualTo("1");

        JsonNode second = ok(send(own, "GET",
                publications(own) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(ids(second)).containsExactly(oldest).doesNotContainAnyElementsOf(ids(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    /** 固件真实存在但没有历史时是200空页，而不是404。 */
    @Test void returnsEmptyPageForFirmwareWithoutHistory() throws Exception {
        Fixture own = seed();
        for (String path : List.of(uploads(own), publications(own))) {
            JsonNode page = ok(send(own, "GET", path), 200);
            assertThat(page.path("items").isArray()).isTrue();
            assertThat(page.path("items").size()).isZero();
            assertThat(page.path("hasMore").asBoolean()).isFalse();
            assertThat(page.path("nextCursor").isNull()).isTrue();
        }
    }

    /** 不存在与本项目无关的固件都按固件NOT_FOUND隐藏，不泄露跨项目存在性。 */
    @Test void hidesNonexistentAndForeignFirmwareAsNotFound() throws Exception {
        Fixture own = seed(), other = seed();
        cleanedUpload(other, Instant.now().truncatedTo(ChronoUnit.MICROS));
        for (String suffix : List.of("uploads", "publications")) {
            error(send(own, "GET", collection(own, Uuid7.generate(), suffix)), 404, 70001);
            error(send(own, "GET", collection(own, other.firmware(), suffix)), 404, 70001);
        }
    }

    /** 未认证401；不属于该项目的调用方（因而没有ota:read）按项目不存在404拒绝。 */
    @Test void enforcesAuthenticationAndReadScope() throws Exception {
        Fixture own = seed(), other = seed();
        cleanedUpload(own, Instant.now().truncatedTo(ChronoUnit.MICROS));
        for (String path : List.of(uploads(own), publications(own))) {
            assertThat(anonymous("GET", path).statusCode()).isEqualTo(401);
            error(send(other, "GET", path), 404, 50001);
        }
        // 现有OtaAuthorization.requireRead把读取绑定到项目成员资格；VIEWER成员与邻居端点
        // （固件列表、上传会话、发布尝试、信任域摘要）一样可读，本片不改变该边界。
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        ok(send(own, "GET", uploads(own)), 200);
        ok(send(own, "GET", publications(own)), 200);
    }

    /** 非法游标与越界limit都是参数错误，不落成500。 */
    @Test void rejectsMalformedCursorAndOutOfRangeLimit() throws Exception {
        Fixture own = seed();
        error(send(own, "GET", uploads(own) + "?cursor=%E8%BF%99%E4%B8%8D%E6%98%AF%E6%B8%B8%E6%A0%87"), 400, 10001);
        error(send(own, "GET", publications(own) + "?limit=0"), 400, 10001);
    }

    /** 每例自有项目图身份。 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type, UUID model, UUID firmware) { }

    /** 上传会话历史路径。 */
    private static String base(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/firmwares/" + f.firmware();
    }

    /** 任意固件身份下的集合路径，用于跨项目与不存在固件断言。 */
    private static String collection(Fixture f, UUID firmwareId, String suffix) {
        return "/api/v1/projects/" + f.project() + "/ota/firmwares/" + firmwareId + "/" + suffix;
    }

    /** 上传会话集合路径。 */
    private static String uploads(Fixture f) { return base(f) + "/uploads"; }

    /** 发布尝试集合路径。 */
    private static String publications(Fixture f) { return base(f) + "/publications"; }

    /** 响应条目身份按响应顺序。 */
    private static List<UUID> ids(JsonNode page) {
        return java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> UUID.fromString(item.path("id").asText())).toList();
    }

    /** owner只提供合法项目和固件头，历史事实仍通过真实app事务与RLS写入。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate());
        fixtures.add(f);
        JdbcTemplate jdbc = owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)"
                + " VALUES (?,?,'{noop}unused','OTA集合读取测试',now())", f.account(), f.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA集合读取租户')", f.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), f.tenant(), f.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA集合读取项目','sh-1',?)",
                f.project(), f.tenant(), "otacoll_" + f.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), f.project(), f.account());
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA集合读取类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, f.type(), f.tenant(), f.project(), "type_" + f.type(), "product_" + f.type());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, f.model(), f.tenant(), f.project(), f.type(), "a".repeat(64));
        jdbc.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,
                    product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at)
                VALUES (?,?,?,?,?,?,'collection_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
                    'TC_PROPERTY_COMPOSITE_V1','DRAFT',0,now())
                """, f.firmware(), f.tenant(), f.project(), f.account(), f.type(), f.model());
        return f;
    }

    /** 唯一64位小写十六进制摘要。 */
    private static String hex(UUID id) { return id.toString().replace("-", "") + "0".repeat(32); }

    /** 已收束上传会话，唯一活动索引允许同一固件保留多条历史。 */
    private OtaUploadSession cleanedUpload(Fixture f, Instant createdAt) {
        UUID id = Uuid7.generate();
        String digest = hex(id);
        UUID token = Uuid7.generate();
        // 触发器和唯一活动索引都要求插入时是WAITING，因此历史夹具也走真实状态机收束。
        OtaUploadSession waiting = new OtaUploadSession(id, f.tenant(), f.project(), f.firmware(), f.account(),
                Uuid7.generate(), 0, 1024, 0, digest, "ota-collection-bucket", "collection/" + id, "WAITING",
                null, null, digest, digest, createdAt, createdAt.plusSeconds(3600), null, null, null, null,
                null, createdAt.plusSeconds(3600), null);
        upload(f, repo -> {
            repo.create(waiting);
            return true;
        });
        upload(f, repo -> repo.claimReceive(waiting, token));
        upload(f, repo -> repo.fail(waiting, token, "RECEIVE_FAILED", false));
        return waiting;
    }

    /** 已拒绝发布尝试，唯一活动索引允许同一固件保留多条历史。 */
    private OtaPublication rejectedPublication(Fixture f, OtaUploadSession upload, Instant createdAt) {
        OtaPublication value = new OtaPublication(Uuid7.generate(), f.tenant(), f.project(), f.firmware(),
                upload.id(), f.account(), Uuid7.generate(), 0, 0, upload.revision(),
                new byte[] {1}, new byte[] {2}, "PREPARED", 0, null, null, null, null,
                null, null, createdAt, createdAt);
        app(f, jdbc -> {
            new JdbcOtaPublicationRepository(jdbc).create(value);
            // 无外部signer的夹具：插入仍走真实PREPARED路径，再在同一事务内按reject()的字段走到终态。
            jdbc.update("UPDATE ota_firmware_publication SET status='REJECTED',revision=revision+1,failure_code=?"
                    + " WHERE project_id=? AND firmware_id=? AND id=? AND status='PREPARED'",
                    "PUBLISH_REJECTED", f.project(), f.firmware(), value.id());
            return true;
        });
        return value;
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

    /** 普通app事务持久上传历史。 */
    private static <T> T upload(Fixture f, Function<JdbcOtaUploadRepository, T> work) {
        return app(f, jdbc -> work.apply(new JdbcOtaUploadRepository(jdbc)));
    }

    /** 在真实普通连接的原事务中建立项目RLS。 */
    private static <T> T app(Fixture f, Function<JdbcTemplate, T> work) {
        return plain(jdbc -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
            return work.apply(jdbc);
        });
    }

    /** 无默认scope普通连接。 */
    private static <T> T plain(Function<JdbcTemplate, T> work) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        var jdbc = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 子先父后清理本例全部事实。 */
    @AfterEach void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                for (String table : List.of("ota_firmware_release", "ota_firmware_publication",
                        "ota_firmware_upload_session", "ota_firmware_creation_request", "ota_firmware")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
                owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM sys_project WHERE id = ?", fixture.project());
                owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenant());
                owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenant());
                owner.update("DELETE FROM sys_account WHERE id = ?", fixture.account());
            });
        }
        fixtures.clear();
    }
}
