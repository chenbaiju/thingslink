package com.things.link.bootstrap.telemetry.event;

import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.EventIngestionService;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 自有PG/普通APP角色与真实JWT过滤器；每条历史只由原事务事件摄取服务提交。 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(DeviceEventHistoryHttpTests.Configuration.class)
@OwnedTestContainers({"DATABASE"})
class DeviceEventHistoryHttpTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("event_history_" + UUID.randomUUID().toString().replace("-", ""))
            .withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    private static final String URL = start();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SNAPSHOT = """
            {"properties":{},"events":{"alarm":{"level":"WARNING","parameters":{
              "temperature":{"dataType":"NUMBER","required":true},
              "sensor_token":{"dataType":"TEXT","required":false}}},
              "empty":{"level":"INFO","parameters":{}},"fault":{"level":"ERROR","parameters":{}}},"commands":{}}
            """;
    private static final List<String> ITEM_FIELDS = List.of("messageId", "deviceId", "deviceTypeId", "eventKey", "level",
            "thingModelVersionId", "modelVersion", "eligibility", "occurredAt", "receivedAt", "acceptedAt", "params", "paramsRedacted");
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfill;
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTasks;
    @MockitoBean(enforceOverride = true) private com.things.link.ingestion.application.RealtimeKafkaPublisher unusedRealtime;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private TenantProvisioning tenants;
    @Autowired private JwtTokenIssuer issuer;
    @Autowired private EventIngestionService events;
    @Autowired private ThingModelVersionBindingService bindings;
    private Fixture f;
    private UUID foreignHome, collaborator;

    @BeforeEach void fixture() throws Exception {
        f = fixtureFor(null);
        foreignHome = transactions.execute(s -> tenants.createTenant("事件历史协作者归属"));
        collaborator = account(foreignHome);
        try (Connection connection = fixtureOwnerConnection()) {
            JdbcTemplate owner = owner(connection);
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')", Uuid7.generate(), f.project(), collaborator);
            // 只在本类独占PG准备读取速率预算；不替换认证/生命周期/历史权益判定。
            owner.update("UPDATE sys_quota_policy SET rest_api_read_rate_per_second=100000,rest_api_read_rate_per_minute=100000");
            owner.queryForList("SELECT alter_job(job_id,scheduled=>false) FROM timescaledb_information.jobs");
        }
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE.getDatabaseName());
    }
    @AfterEach void clearIdentity() { TenantContext.clear(); }
    @Override protected Connection fixtureOwnerConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(URL, DATABASE.getUsername(), DATABASE.getPassword());
    }

    @ParameterizedTest
    @CsvSource({"OWNER,ACTIVE", "ADMIN,ACTIVE", "OPERATOR,ACTIVE", "VIEWER,ACTIVE",
            "OWNER,ARCHIVED", "ADMIN,ARCHIVED", "OPERATOR,ARCHIVED", "VIEWER,ARCHIVED"})
    void currentFourRolesReadBothLifecycleStates(String role, String lifecycle) throws Exception {
        UUID message = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, f.project(), f.actor());
            owner(connection).update("UPDATE sys_project SET status=? WHERE id=?", lifecycle, f.project());
        }
        String token = token(f.actor(), f.tenant(), f.project());
        assertThat(list(token, f.project(), f.device(), 200).path("items").get(0).path("messageId").asString()).isEqualTo(message.toString());
        assertThat(detail(token, f.project(), f.device(), message, 200).path("eligibility").asString()).isEqualTo("CURRENT");
    }

    @Test void crossHomeTenantUsesOwnerWindowAndCurrentMembership() throws Exception {
        UUID old = fact(f, f.device(), "empty", recent().minus(8, ChronoUnit.DAYS), Map.of(), "1.0.0");
        String token = token(collaborator, foreignHome, f.project());
        assertThat(list(token, f.project(), f.device(), 200).path("items").isEmpty()).isTrue();
        addWindow(foreignHome, collaborator, 30);
        assertThat(list(token, f.project(), f.device(), 200).path("items").isEmpty()).isTrue();
        addWindow(f.tenant(), f.actor(), 3);
        assertThat(list(token, f.project(), f.device(), 200).path("items").get(0).path("messageId").asString()).isEqualTo(old.toString());
        assertThat(detail(token, f.project(), f.device(), old, 200).path("messageId").asString()).isEqualTo(old.toString());
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", f.project(), collaborator);
        }
        assertCode(list(token, f.project(), f.device(), 401), 20020);
        assertCode(detail(token, f.project(), f.device(), old, 401), 20020);
    }

    @Test void deletingAndOldGenerationCannotReuseValidSignedProjectToken() throws Exception {
        UUID id = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        String old = token(f.actor(), f.tenant(), f.project());
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_project SET status='DELETING',deleted_at=clock_timestamp(),lifecycle_generation=lifecycle_generation+1 WHERE id=?", f.project());
        }
        assertCode(list(old, f.project(), f.device(), 401), 20020);
        assertCode(detail(old, f.project(), f.device(), id, 401), 20020);
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_project SET status='ACTIVE',deleted_at=NULL WHERE id=?", f.project());
        }
        assertCode(list(old, f.project(), f.device(), 401), 20020);
        String current = issuer.issue(new AuthenticatedPrincipal(f.actor(), f.tenant(), f.project(), 1)).value();
        assertThat(list(current, f.project(), f.device(), 200).path("items")).hasSize(1);
    }

    @Test void invisibleMessageCausesShareOne404WithoutHidingParentAuthorizationFailure() throws Exception {
        UUID id = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        UUID expired = fact(f, f.device(), "empty", recent().minus(8, ChronoUnit.DAYS), Map.of(), "1.0.0");
        Fixture other = fixtureFor(f.actor());
        String token = token(f.actor(), f.tenant(), f.project());
        assertCode(detail(token, f.project(), f.secondDevice(), id, 404), 30072);
        assertCode(detail(token, f.project(), f.device(), Uuid7.generate(), 404), 30072);
        assertCode(detail(token, f.project(), f.device(), expired, 404), 30072);
        assertCode(detail(token(f.actor(), f.tenant(), other.project()), other.project(), other.device(), id, 404), 30072);
        assertCode(list(token, f.project(), Uuid7.generate(), 404), 10004);
        assertCode(list(token, f.project(), other.device(), 404), 10004);
        assertCode(detail(token, f.project(), other.device(), id, 404), 10004);
        list(null, f.project(), f.device(), 401);
        detail(null, f.project(), f.device(), id, 401);
    }

    @Test void publicClosedDtoRetainsOnlyRedactedOriginalFacts() throws Exception {
        UUID id = fact(f, f.device(), "alarm", recent(), Map.of("temperature", 12, "sensor_token", "synthetic-hidden-token"), "1.0.0");
        UUID empty = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode page = list(token, f.project(), f.device(), 200);
        assertPage(page);
        for (JsonNode item : page.path("items")) assertPublic(item);
        JsonNode item = detail(token, f.project(), f.device(), id, 200);
        assertPublic(item);
        assertThat(item.path("paramsRedacted").asBoolean()).isTrue();
        assertThat(item.path("params").has("sensor_token")).isFalse();
        assertThat(item.path("params").path("temperature").asInt()).isEqualTo(12);
        JsonNode emptyItem = detail(token, f.project(), f.device(), empty, 200);
        assertThat(emptyItem.path("params").isEmpty()).isTrue();
        assertThat(emptyItem.path("paramsRedacted").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "12345678901234567890123456789012345678", "0.12345678901234567890123456789012345678", "1e-308", "1.0"})
    void actualIngestionPgAndHttpPreserveNumbersThroughBothRedactionPasses(String text) throws Exception {
        var expected = new java.math.BigDecimal(text);
        UUID id = fact(f, f.device(), "alarm", recent(), Map.of("temperature", expected,
                "sensor_token", "synthetic-number-secret"), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        ObjectMapper exact = new ObjectMapper().rebuild()
                .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        try (Connection connection = fixtureOwnerConnection()) {
            assertThat(owner(connection).queryForObject("SELECT params->>'temperature' FROM ts_device_event WHERE message_id=?",
                    String.class, id)).isEqualTo(expected.toPlainString());
        }
        for (String path : List.of("/api/v1/projects/" + f.project() + "/devices/" + f.device() + "/events",
                "/api/v1/projects/" + f.project() + "/devices/" + f.device() + "/events/" + id)) {
            var response = mvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            String raw = response.getContentAsString();
            assertThat(raw).doesNotContain("sensor_token", "synthetic-number-secret");
            var body = exact.readTree(raw);
            var item = body.has("items") ? body.get("items").get(0) : body;
            assertThat(item.get("params").get("temperature").decimalValue()).isEqualTo(expected);
            assertThat(item.get("paramsRedacted").asBoolean()).isTrue();
        }
    }

    @Test void sameOccurrenceUuidOrderingFiltersAndLimitPlusOneAreActualFacts() throws Exception {
        Instant time = recent();
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) expected.add(fact(f, f.device(), "alarm", time, Map.of("temperature", i), "1.0.0"));
        fact(f, f.device(), "empty", time.plusSeconds(1), Map.of(), "1.0.0");
        fact(f, f.device(), "fault", time.plusSeconds(2), Map.of(), "1.0.0");
        fact(f, f.secondDevice(), "alarm", time, Map.of("temperature", 100), "1.0.0");
        expected.sort(Comparator.comparing(UUID::toString).reversed());
        List<String> seen = new ArrayList<>(); String cursor = null;
        do {
            List<String> query = new ArrayList<>(List.of("eventKey", "alarm", "level", "WARNING", "thingModelVersionId", f.version().toString(),
                    "from", time.toString(), "to", time.plusSeconds(1).toString(), "limit", "2"));
            if (cursor != null) { query.add("cursor"); query.add(cursor); }
            JsonNode page = list(token(f.actor(), f.tenant(), f.project()), f.project(), f.device(), 200, query.toArray(String[]::new));
            assertPage(page);
            page.path("items").forEach(item -> seen.add(item.path("messageId").asString()));
            cursor = cursor(page);
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
        assertThat(list(token(f.actor(), f.tenant(), f.project()), f.project(), f.device(), 200, "level", "ERROR").path("items")).hasSize(1);
        assertThat(list(token(f.actor(), f.tenant(), f.project()), f.project(), f.device(), 200, "thingModelVersionId", Uuid7.generate().toString()).path("items").isEmpty()).isTrue();
    }

    @Test void defaultsToTwentyAndAcceptsHundredWithoutInventingTotal() throws Exception {
        for (int i = 0; i < 21; i++) fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode first = list(token, f.project(), f.device(), 200);
        assertThat(first.path("items")).hasSize(20); assertThat(cursor(first)).isNotNull();
        JsonNode all = list(token, f.project(), f.device(), 200, "limit", "100");
        assertThat(all.path("items")).hasSize(21); assertThat(cursor(all)).isNull(); assertPage(all);
    }

    @Test void closedQueryAndCursorBudgetsRejectInvalidInputsEvenForEmptyIntersection() throws Exception {
        String token = token(f.actor(), f.tenant(), f.project());
        for (String[] bad : List.of(new String[]{"unknown", "x"}, new String[]{"level", "info"}, new String[]{"level", ""}, new String[]{"eventKey", ""}, new String[]{"eventKey", "bad.key"},
                new String[]{"thingModelVersionId", "not-uuid"}, new String[]{"from", "2026-02-30T00:00:00Z"},
                new String[]{"from", "2026-01-01T00:00:00"}, new String[]{"from", ""},
                new String[]{"from", "2026-01-02T00:00:00Z", "to", "2026-01-01T00:00:00Z"}, new String[]{"from", "2026-01-01T00:00:00Z", "to", "2026-01-01T00:00:00Z"},
                new String[]{"limit", "0"}, new String[]{"limit", "101"}, new String[]{"limit", "-1"}, new String[]{"limit", "bad"},
                new String[]{"limit", "1", "limit", "2"}, new String[]{"eventKey", "empty", "eventKey", "empty"},
                new String[]{"cursor", ""}, new String[]{"cursor", "!bad"}, new String[]{"cursor", "A".repeat(8193)},
                new String[]{"cursor", encode("[]")}, new String[]{"cursor", encode("{}")},
                new String[]{"from", "2000-01-01T00:00:00Z", "to", "2000-01-02T00:00:00Z", "cursor", "!bad"}))
            assertCode(list(token, f.project(), f.device(), 400, bad), 10001);
        assertCode(list(token, f.project(), f.device(), 400, "cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{(byte) 0xc3, 0x28})), 10001);
        UUID id = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        assertCode(detail(token, f.project(), f.device(), id, 400, "limit", "1"), 10001);
    }

    @Test void cursorBindsEveryOriginalFilterAndScopeButNotReaderOrLimit() throws Exception {
        Instant time = recent();
        for (int i = 0; i < 3; i++) fact(f, f.device(), "alarm", time, Map.of("temperature", i), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        List<String> original = List.of("eventKey", "alarm", "level", "WARNING", "thingModelVersionId", f.version().toString(),
                "from", time.minusSeconds(1).toString(), "to", time.plusSeconds(1).toString(), "limit", "1");
        String cursor = cursor(list(token, f.project(), f.device(), 200, original.toArray(String[]::new)));
        assertThat(cursor).isNotNull();
        for (String key : List.of("eventKey", "level", "thingModelVersionId", "from", "to")) {
            List<String> changed = new ArrayList<>(original);
            changed.set(changed.indexOf(key) + 1, switch (key) {
                case "eventKey" -> "empty"; case "level" -> "INFO"; case "thingModelVersionId" -> Uuid7.generate().toString();
                case "from" -> time.minusSeconds(2).toString(); default -> time.plusSeconds(2).toString();
            });
            changed.add("cursor"); changed.add(cursor);
            assertCode(list(token, f.project(), f.device(), 400, changed.toArray(String[]::new)), 10001);
        }
        List<String> withCursor = new ArrayList<>(original); withCursor.add("cursor"); withCursor.add(cursor);
        assertCode(list(token, f.project(), f.secondDevice(), 400, withCursor.toArray(String[]::new)), 10001);
        Fixture other = fixtureFor(f.actor());
        assertCode(list(token(f.actor(), f.tenant(), other.project()), other.project(), other.device(), 400, withCursor.toArray(String[]::new)), 10001);
        List<String> changedLimit = new ArrayList<>(withCursor); changedLimit.set(changedLimit.indexOf("limit") + 1, "2");
        assertThat(list(token(collaborator, foreignHome, f.project()), f.project(), f.device(), 200, changedLimit.toArray(String[]::new)).path("items")).hasSize(2);
        List<String> equivalentOffset = new ArrayList<>(withCursor);
        equivalentOffset.set(equivalentOffset.indexOf("from") + 1, time.minusSeconds(1).toString().replace("Z", "+00:00"));
        assertCode(list(token, f.project(), f.device(), 400, equivalentOffset.toArray(String[]::new)), 10001);
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertCode(list(token, f.project(), f.device(), 400, replaceCursor(withCursor, encode(decoded.substring(0, decoded.length()-1) + ",\"extra\":1}"))), 10001);
    }

    @Test void ownerQuotaAndNinetyDaysClipDatabaseNowWithoutDeletingPhysicalFacts() throws Exception {
        Instant time = recent();
        UUID fresh = fact(f, f.device(), "empty", time, Map.of(), "1.0.0");
        UUID eight = fact(f, f.device(), "empty", time.minus(8, ChronoUnit.DAYS), Map.of(), "1.0.0");
        UUID eighty = fact(f, f.device(), "empty", time.minus(80, ChronoUnit.DAYS), Map.of(), "1.0.0");
        UUID ninetyFive = fact(f, f.device(), "empty", time.minus(95, ChronoUnit.DAYS), Map.of(), "1.0.0");
        UUID future = fact(f, f.device(), "empty", Instant.now().plusSeconds(120), Map.of(), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode free = list(token, f.project(), f.device(), 200);
        assertThat(ids(free)).containsExactly(fresh.toString());
        assertCode(detail(token, f.project(), f.device(), eight, 404), 30072);
        addWindow(f.tenant(), f.actor(), 100);
        Instant before = databaseNow();
        JsonNode expanded = list(token, f.project(), f.device(), 200, "from", time.minus(200, ChronoUnit.DAYS).toString(), "to", time.plus(5, ChronoUnit.DAYS).toString());
        Instant after = databaseNow();
        assertPage(expanded);
        assertThat(ids(expanded)).containsExactly(fresh.toString(), eight.toString(), eighty.toString());
        assertThat(Instant.parse(expanded.path("windowTo").asString())).isBetween(before, after);
        assertThat(Instant.parse(expanded.path("windowFrom").asString())).isBetween(before.minus(90, ChronoUnit.DAYS), after.minus(90, ChronoUnit.DAYS));
        assertCode(detail(token, f.project(), f.device(), ninetyFive, 404), 30072);
        assertCode(detail(token, f.project(), f.device(), future, 404), 30072);
        assertThat(list(token, f.project(), f.device(), 200, "from", time.minus(200, ChronoUnit.DAYS).toString(), "to", time.minus(100, ChronoUnit.DAYS).toString()).path("items").isEmpty()).isTrue();
        JsonNode emptyFuture = list(token, f.project(), f.device(), 200, "from", Instant.now().plusSeconds(600).toString(), "to", Instant.now().plusSeconds(700).toString());
        assertPage(emptyFuture); assertThat(emptyFuture.path("items").isEmpty()).isTrue();
        try (Connection connection = fixtureOwnerConnection()) {
            assertThat(owner(connection).queryForObject("SELECT count(*) FROM ts_device_event WHERE project_id=?", Long.class, f.project())).isEqualTo(5);
        }
    }

    @Test void expiryShrinksSecondPageDespiteOriginalCursorAndMissingQuotaFailsClosed() throws Exception {
        addWindow(f.tenant(), f.actor(), 3);
        UUID first = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        UUID old = fact(f, f.device(), "empty", recent().minus(8, ChronoUnit.DAYS), Map.of(), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode page = list(token, f.project(), f.device(), 200, "limit", "1");
        assertThat(ids(page)).containsExactly(first.toString()); assertThat(cursor(page)).isNotNull();
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_tenant_resource_package SET ends_at=clock_timestamp()-interval '1 second' WHERE tenant_id=?", f.tenant());
        }
        assertThat(list(token, f.project(), f.device(), 200, "limit", "1", "cursor", cursor(page)).path("items").isEmpty()).isTrue();
        assertCode(detail(token, f.project(), f.device(), old, 404), 30072);
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?", f.tenant());
        }
        assertCode(list(token, f.project(), f.device(), 503), 50048);
        assertCode(detail(token, f.project(), f.device(), first, 503), 50048);
    }

    @Test void inclusiveStartExclusiveEndUsesPersistedMicrosecondOccurrence() throws Exception {
        Instant time = recent();
        UUID included = fact(f, f.device(), "empty", time, Map.of(), "1.0.0");
        fact(f, f.device(), "empty", time.plusSeconds(1), Map.of(), "1.0.0");
        JsonNode page = list(token(f.actor(), f.tenant(), f.project()), f.project(), f.device(), 200,
                "from", time.toString(), "to", time.plusSeconds(1).toString());
        assertThat(ids(page)).containsExactly(included.toString());
    }

    @Test void nanosecondInputRoundedToSamePgMicrosecondPagesWithoutSkipping() throws Exception {
        Instant base = recent().truncatedTo(ChronoUnit.SECONDS);
        UUID one = fact(f, f.device(), "empty", base.plusNanos(123456001), Map.of(), "1.0.0");
        UUID two = fact(f, f.device(), "empty", base.plusNanos(123456499), Map.of(), "1.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode first = list(token, f.project(), f.device(), 200, "limit", "1");
        JsonNode second = list(token, f.project(), f.device(), 200, "limit", "1", "cursor", cursor(first));
        List<String> expected = new ArrayList<>(List.of(one.toString(), two.toString())); expected.sort(Comparator.reverseOrder());
        assertThat(ids(first)).containsExactly(expected.get(0)); assertThat(ids(second)).containsExactly(expected.get(1));
        assertThat(cursor(second)).isNull();
        assertThat(Instant.parse(first.path("items").get(0).path("occurredAt").asString())).isEqualTo(base.plusNanos(123456000));
        assertThat(Instant.parse(second.path("items").get(0).path("occurredAt").asString())).isEqualTo(base.plusNanos(123456000));
    }

    @Test void realBindingUpgradeCannotReinterpretFrozenModelOrHistoryEligibility() throws Exception {
        UUID original = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        UUID next = Uuid7.generate();
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("""
                    INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                      version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                    VALUES (?,?,?,?,'2.0.0',2,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                      encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                    """, next, f.tenant(), f.project(), f.type(), SNAPSHOT, SNAPSHOT);
        }
        TenantContext.set(new TenantScope(f.tenant(), f.project(), f.actor()));
        try { bindings.bind(f.project(), f.device(), next, Uuid7.generate(), TransitionType.UPGRADE, Instant.now().minusSeconds(1)); }
        finally { TenantContext.clear(); }
        UUID history = fact(f, f.device(), "empty", recent(), Map.of(), "1.0.0");
        UUID current = fact(f, f.device(), "empty", recent(), Map.of(), "2.0.0");
        String token = token(f.actor(), f.tenant(), f.project());
        JsonNode frozen = detail(token, f.project(), f.device(), original, 200);
        assertThat(frozen.path("thingModelVersionId").asString()).isEqualTo(f.version().toString());
        assertThat(frozen.path("modelVersion").asString()).isEqualTo("1.0.0");
        assertThat(frozen.path("eligibility").asString()).isEqualTo("CURRENT");
        assertThat(detail(token, f.project(), f.device(), history, 200).path("eligibility").asString()).isEqualTo("HISTORY_ONLY");
        assertThat(detail(token, f.project(), f.device(), current, 200).path("thingModelVersionId").asString()).isEqualTo(next.toString());
        assertThat(ids(list(token, f.project(), f.device(), 200, "thingModelVersionId", f.version().toString()))).containsExactlyInAnyOrder(original.toString(), history.toString());
    }

    private UUID fact(Fixture fixture, UUID target, String key, Instant occurred, Map<String,Object> params, String model) {
        UUID id = Uuid7.generate();
        assertThat(events.ingest(new EventUplinkMessage(id, fixture.tenant(), fixture.project(), target, TransportProtocol.MQTT,
                key, model, occurred, Instant.now(), "history-http", 128, params))).isTrue();
        return id;
    }
    private Fixture fixtureFor(UUID existingActor) throws Exception {
        UUID tenant = transactions.execute(s -> tenants.createTenant("事件历史项目计费归属"));
        UUID actor = existingActor == null ? account(tenant) : existingActor;
        UUID project = Uuid7.generate(), type = Uuid7.generate(), device = Uuid7.generate(), second = Uuid7.generate();
        try (Connection connection = fixtureOwnerConnection()) {
            JdbcTemplate owner = owner(connection);
            owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'事件历史','sh-1',?)", project, tenant, "eh_" + project.toString().replace("-", ""));
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", Uuid7.generate(), project, actor);
            owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'event_history','事件历史','STANDARD','DIRECT','PUBLISHED')", type, tenant, project);
            for (UUID id : List.of(device, second)) owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,'事件历史设备','ONLINE')", id, tenant, project, type, id.toString());
        }
        UUID version = seedThingModelVersion(tenant, project, type, device, SNAPSHOT);
        seedThingModelVersion(tenant, project, type, second, SNAPSHOT);
        return new Fixture(tenant, project, actor, type, device, second, version);
    }
    private UUID account(UUID tenant) throws Exception {
        UUID id = Uuid7.generate();
        try (Connection connection = fixtureOwnerConnection()) {
            JdbcTemplate owner = owner(connection);
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at) VALUES (?,?,'test-only','事件HTTP读取','ACTIVE',clock_timestamp())", id, id + "@example.test");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), tenant, id);
        }
        return id;
    }
    private void addWindow(UUID tenant, UUID actor, int days) throws Exception {
        try (Connection connection = fixtureOwnerConnection()) {
            owner(connection).update("""
                    INSERT INTO sys_tenant_resource_package(id,tenant_id,dimension_code,amount,unit,window_kind,
                      starts_at,ends_at,source,status,adjustment_reason,adjustment_operator_id,adjustment_key)
                    VALUES (?,?,'HISTORY_WINDOW',?,'DAY','ROLLING',clock_timestamp()-interval '1 hour',clock_timestamp()+interval '1 hour',
                      'OPERATION_ADJUSTMENT','ACTIVE','事件HTTP窗口夹具',?,?)
                    """, Uuid7.generate(), tenant, days, actor, Uuid7.generate().toString());
        }
    }
    private Instant databaseNow() throws Exception {
        try (Connection connection = fixtureOwnerConnection()) {
            return owner(connection).queryForObject("SELECT statement_timestamp()", Timestamp.class).toInstant();
        }
    }
    private String token(UUID actor, UUID home, UUID project) { return issuer.issue(new AuthenticatedPrincipal(actor, home, project)).value(); }
    private JsonNode list(String token, UUID project, UUID device, int status, String... query) throws Exception {
        return read(get("/api/v1/projects/{projectId}/devices/{deviceId}/events", project, device), token, status, query);
    }
    private JsonNode detail(String token, UUID project, UUID device, UUID message, int status, String... query) throws Exception {
        return read(get("/api/v1/projects/{projectId}/devices/{deviceId}/events/{messageId}", project, device, message), token, status, query);
    }
    private JsonNode read(MockHttpServletRequestBuilder request, String token, int status, String... query) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        for (int i = 0; i < query.length; i += 2) request.param(query[i], query[i+1]);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return JSON.readTree(response.getContentAsString());
    }
    private static void assertCode(JsonNode response, int code) { assertThat(response.path("code").asInt()).isEqualTo(code); }
    private static void assertPage(JsonNode page) {
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "windowFrom", "windowTo", "retentionDays");
        assertThat(page.path("windowFrom").isString()).isTrue(); assertThat(page.path("windowTo").isString()).isTrue();
        assertThat(page.path("retentionDays").asInt()).isEqualTo(90);
    }
    private static void assertPublic(JsonNode item) {
        assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(ITEM_FIELDS);
        assertThat(item.toString()).doesNotContain("synthetic-hidden-token", "tenantId", "projectId", "inputDigest", "rawBytes", "traceId", "topic", "schemaDigest");
    }
    private static List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>(); page.path("items").forEach(item -> result.add(item.path("messageId").asString())); return result;
    }
    private static String cursor(JsonNode page) { return page.path("nextCursor").isNull() || page.path("nextCursor").isMissingNode() ? null : page.path("nextCursor").asString(); }
    private static String encode(String json) { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)); }
    private static String[] replaceCursor(List<String> query, String cursor) {
        List<String> result = new ArrayList<>(query); result.set(result.indexOf("cursor") + 1, cursor); return result.toArray(String[]::new);
    }
    private static Instant recent() { return Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS); }
    private static JdbcTemplate owner(Connection connection) { return new JdbcTemplate(new SingleConnectionDataSource(connection, true)); }
    private record Fixture(UUID tenant, UUID project, UUID actor, UUID type, UUID device, UUID secondDevice, UUID version) {}
    private static String start() { DATABASE.start(); return DATABASE.getJdbcUrl(); }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean DynamicPropertyRegistrar eventHistoryDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> URL); registry.add("spring.flyway.url", () -> URL);
                registry.add("spring.flyway.user", DATABASE::getUsername); registry.add("spring.flyway.password", DATABASE::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("things-link.project.cleanup.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }
}
