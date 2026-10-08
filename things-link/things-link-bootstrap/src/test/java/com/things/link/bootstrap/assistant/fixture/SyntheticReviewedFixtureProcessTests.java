package com.things.link.bootstrap.assistant.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 只验证公开启动材料，不创建连接池、种子或服务。 */
class SyntheticReviewedFixtureProcessTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT = "00000000-0000-4000-8000-000000000001";
    private static final String PROJECT = "00000000-0000-4000-8000-000000000002";
    private static final String DEVICE = "00000000-0000-4000-8000-000000000003";

    @Test
    void acceptsExactPublicScopeAndCopiesItsCollections() throws Exception {
        var value = manifest();
        var parsed = parse(value);
        assertThat(parsed.database()).isEqualTo("tc_console_010c_fixture");
        assertThat(parsed.appRole()).isEqualTo("thingslink_app");
        assertThat(parsed.targets()).hasSize(1);
        assertThat(parsed.targets().getFirst().actors().keySet()).isEqualTo(Set.of("OWNER", "ADMIN", "OPERATOR", "VIEWER"));
        value.remove("targets");
        assertThat(parsed.targets()).hasSize(1);
        assertThatThrownBy(() -> parsed.targets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parsed.targets().getFirst().actors().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesOldPortsOrStringFractionAndOverflowPortCoercion() throws Exception {
        for (String invalidPort : List.of("8088", "8080", "\"8089\"", "8089.1", "4294975385", "null")) {
            rejected(JSON.writeValueAsString(manifest()).replace("\"apiPort\":8089", "\"apiPort\":" + invalidPort));
        }
        var value = manifest();
        value.put("uiPort", 3017);
        rejected(value);
        value.put("uiPort", 3006);
        rejected(value);
    }

    @Test
    void refusesSharedDatabaseMalformedRoleAndWrongQualification() throws Exception {
        for (String database : List.of("thingslink", "tc_console_016c_test", "tc_console_010c_", "tc_console_010c_a?user=owner")) {
            var value = manifest();
            value.put("database", database);
            rejected(value);
        }
        var value = manifest();
        value.put("appRole", "role;SET ROLE owner");
        rejected(value);
        value = manifest();
        value.put("qualification", "PRODUCTION_APPROVED");
        rejected(value);
    }

    @Test
    void rejectsClosedSchemaDuplicatesTrailingTokensWrongTypesAndDigestMismatch() throws Exception {
        var value = manifest();
        value.put("modelKey", "synthetic-but-forbidden");
        rejected(value);
        String valid = JSON.writeValueAsString(manifest());
        rejected(valid.replace("\"database\":", "\"database\":\"tc_console_010c_other\",\"database\":"));
        rejected(valid.replace("\"database\":", "\"data\\u0062ase\":\"tc_console_010c_other\",\"database\":"));
        rejected(valid + " {}");
        rejected("[]");
        value = manifest();
        value.put("database", 123);
        rejected(value);
        byte[] raw = JSON.writeValueAsBytes(manifest());
        assertThatThrownBy(() -> SyntheticReviewedFixtureProcess.parseManifest(raw, "0".repeat(64)))
                .hasMessage("INVALID_SYNTHETIC_FIXTURE").hasNoCause();
        assertThatThrownBy(() -> SyntheticReviewedFixtureProcess.parseManifest(new byte[16385], "0".repeat(64)))
                .hasMessage("INVALID_SYNTHETIC_FIXTURE").hasNoCause();
    }

    @Test
    void rejectsNoncanonicalUuidDuplicateDeviceForeignFaultAndRoleExpansion() throws Exception {
        var value = manifest();
        target(value).put("tenantId", "0-0-4000-8000-1");
        rejected(value);
        value = manifest();
        target(value).withArray("deviceIds").add(DEVICE);
        rejected(value);
        value = manifest();
        target(value).withArray("unknownDeviceIds").add("00000000-0000-4000-8000-000000000099");
        rejected(value);
        value = manifest();
        ((ObjectNode) target(value).get("actors")).put("SUPER_ADMIN", "00000000-0000-4000-8000-000000000099");
        rejected(value);
        value = manifest();
        ((ObjectNode) target(value).get("actors")).put("VIEWER", "00000000-0000-4000-8000-000000000010");
        rejected(value);
    }

    @Test
    void refusesPartialScopesAndDuplicateProjectsInsteadOfExpandingRuntimeWhitelist() throws Exception {
        var value = manifest();
        target(value).put("credential", "not-allowed");
        rejected(value);
        value = manifest();
        value.withArray("targets").add(target(value).deepCopy());
        rejected(value);
        value = manifest();
        value.withArray("targets").removeAll();
        rejected(value);
        value = manifest();
        target(value).withArray("deviceIds").removeAll();
        rejected(value);
    }

    @Test
    void ordinaryTestClasspathDoesNotEnableFixtureConfiguration() {
        var condition = SyntheticReviewedFixtureProcess.FixtureConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("tc.010c.fixture-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void commandLineCannotDisableFixtureGuardOrOverrideItsListener() {
        String previous = System.getProperty("tc.010c.fixture-enabled");
        for (String argument : List.of("--tc.010c.fixture-enabled=false", "--server.port=8088",
                "--spring.datasource.url=jdbc:postgresql://localhost:5547/shared")) {
            assertThatThrownBy(() -> SyntheticReviewedFixtureProcess.main(new String[]{argument}))
                    .hasMessage("INVALID_SYNTHETIC_FIXTURE").hasNoCause();
        }
        assertThat(System.getProperty("tc.010c.fixture-enabled")).isEqualTo(previous);
    }

    private static ObjectNode manifest() {
        var value = JSON.createObjectNode();
        value.put("qualification", "010-C-SYNTHETIC-REVIEWED-INTERACTION");
        value.put("database", "tc_console_010c_fixture");
        value.put("appRole", "thingslink_app");
        value.put("apiPort", 8089).put("uiPort", 3018);
        var target = value.putArray("targets").addObject();
        target.put("tenantId", TENANT).put("projectId", PROJECT);
        var actors = target.putObject("actors");
        actors.put("OWNER", "00000000-0000-4000-8000-000000000010");
        actors.put("ADMIN", "00000000-0000-4000-8000-000000000011");
        actors.put("OPERATOR", "00000000-0000-4000-8000-000000000012");
        actors.put("VIEWER", "00000000-0000-4000-8000-000000000013");
        target.putArray("deviceIds").add(DEVICE);
        target.putArray("unknownDeviceIds");
        return value;
    }
    private static ObjectNode target(ObjectNode document) {
        return (ObjectNode) document.path("targets").get(0);
    }
    private static SyntheticReviewedFixtureProcess.FixtureManifest parse(ObjectNode value) throws Exception {
        byte[] raw = JSON.writeValueAsBytes(value);
        return SyntheticReviewedFixtureProcess.parseManifest(raw, sha(raw));
    }
    private static void rejected(ObjectNode value) throws Exception { rejected(JSON.writeValueAsString(value)); }
    private static void rejected(String value) throws Exception {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        String digest = sha(raw);
        assertThatThrownBy(() -> SyntheticReviewedFixtureProcess.parseManifest(raw, digest))
                .hasMessage("INVALID_SYNTHETIC_FIXTURE").hasNoCause();
    }
    private static String sha(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
}
