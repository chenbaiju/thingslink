package com.things.link.dashboard.application.publication;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 封存需求的严格语义反例，不把应用宽范围误作精确组件/资源资格。 */
class DashboardHostCompatibilityTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DashboardHostQualificationDescriptor HOST = new DashboardHostQualificationDescriptor(
            "tc.webapp-host/v1", "1.1.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
            Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.1"), Map.of("mark", "a".repeat(64)));
    private static ObjectNode metadata() {
        return (ObjectNode) JSON.readTree("""
                {"format":"tc.application/v1","hostCompatibility":{"minInclusive":"1.1.0","maxExclusive":"1.1.2"},
                 "schemas":["tc.dashboard/v1"],"components":[{"kind":"TEXT","componentVersion":"1.0.1"}],"resources":[]}
                """);
    }
    private static void rejects(JsonNode value, DashboardHostCompatibility.Reason reason) {
        assertThat(DashboardHostCompatibility.inspect(HOST, DashboardHostCompatibility.Kind.APPLICATION, value)).contains(reason);
    }
    @Test void matchingFactsAndExactResourcePass() {
        var value = metadata();
        value.set("resources", JSON.valueToTree(java.util.List.of(Map.of("resourceId", "mark", "digest", "a".repeat(64)))));
        assertThat(DashboardHostCompatibility.inspect(HOST, DashboardHostCompatibility.Kind.APPLICATION, value)).isEmpty();
        value.put("format", "tc.dashboard/v1");
        assertThat(DashboardHostCompatibility.inspect(HOST, DashboardHostCompatibility.Kind.SHARE, value)).isEmpty();
        value.putNull("hostCompatibility");
        assertThat(DashboardHostCompatibility.inspect(HOST, DashboardHostCompatibility.Kind.DASHBOARD, value)).isEmpty();
        assertThat(DashboardHostCompatibility.inspect(HOST, DashboardHostCompatibility.Kind.SHARE, value)).contains(DashboardHostCompatibility.Reason.INVALID_METADATA);
    }
    @Test void broadApplicationRangeNeverAdoptsAnOldComponent() {
        var value = metadata();
        value.set("hostCompatibility", JSON.readTree("{\"minInclusive\":\"1.0.0\",\"maxExclusive\":\"2.0.0\"}"));
        value.set("components", JSON.readTree("[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.0\"}]"));
        rejects(value, DashboardHostCompatibility.Reason.COMPONENT);
    }
    @Test void closedMetadataCannotBeMissingNullExtendedOrDuplicated() {
        for (String field : Set.of("format", "hostCompatibility", "schemas", "components", "resources")) {
            var missing = metadata(); missing.remove(field); rejects(missing, DashboardHostCompatibility.Reason.INVALID_METADATA);
            var empty = metadata(); empty.putNull(field); rejects(empty, DashboardHostCompatibility.Reason.INVALID_METADATA);
        }
        var extra = metadata(); extra.put("secret", "never projected"); rejects(extra, DashboardHostCompatibility.Reason.INVALID_METADATA);
        var duplicate = metadata(); duplicate.set("components", JSON.readTree("[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.1\"},{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.1\"}]"));
        rejects(duplicate, DashboardHostCompatibility.Reason.INVALID_METADATA);
    }
    @Test void unsupportedFormatsRangesSchemasAndResourcesRemainDistinctFailures() {
        var value = metadata(); value.put("format", "tc.application/v2"); rejects(value, DashboardHostCompatibility.Reason.FORMAT);
        for (String range : new String[]{"{\"minInclusive\":\"1.0.0\",\"maxExclusive\":\"1.1.0\"}",
                "{\"minInclusive\":\"1.1.1\",\"maxExclusive\":\"1.1.2\"}", "{\"minInclusive\":\"01.1.0\",\"maxExclusive\":\"1.1.2\"}"}) {
            value = metadata(); value.set("hostCompatibility", JSON.readTree(range)); rejects(value, DashboardHostCompatibility.Reason.HOST_RANGE);
        }
        value = metadata(); value.set("schemas", JSON.readTree("[\"tc.dashboard/v2\"]")); rejects(value, DashboardHostCompatibility.Reason.SCHEMA);
        value = metadata(); value.set("resources", JSON.valueToTree(java.util.List.of(Map.of("resourceId", "mark", "digest", "b".repeat(64)))));
        rejects(value, DashboardHostCompatibility.Reason.RESOURCE);
    }
}
