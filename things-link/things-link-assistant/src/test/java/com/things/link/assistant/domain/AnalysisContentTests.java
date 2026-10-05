package com.things.link.assistant.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AnalysisContentTests {
    @Test void copiesNestedListsAndHidesContentWithoutClaimingQualification() throws Exception {
        var ids = new ArrayList<>(List.of("private-reference"));
        var finding = new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "private-statement", ids);
        var findings = new ArrayList<>(List.of(finding));
        var limitations = new ArrayList<>(List.of("private-limitation"));
        var content = new AnalysisContent("private-summary", findings, limitations);
        ids.clear(); findings.clear(); limitations.clear();
        assertThat(content.findings()).containsExactly(finding);
        assertThat(finding.evidenceIds()).containsExactly("private-reference");
        assertThat(content.limitations()).containsExactly("private-limitation");
        assertThatThrownBy(() -> content.findings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> finding.evidenceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> content.limitations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(content.toString()).doesNotContain("private");
        assertThat(finding.toString()).doesNotContain("private");
        var json = JsonMapper.builder().build().valueToTree(content);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("summary", "findings", "limitations");
        assertThat(json.path("findings").get(0).path("kind").asString()).isEqualTo("FACT");
        assertThat(json.has("qualification")).isFalse();
    }

    @Test void rejectsInvalidContentWithFixedErrors() {
        var cases = List.<Runnable>of(
                () -> new AnalysisContent(null, List.of(), List.of()),
                () -> new AnalysisContent("", List.of(), List.of()),
                () -> new AnalysisContent("private", null, List.of()),
                () -> new AnalysisContent("private", Arrays.asList((AnalysisContent.Finding) null), List.of()),
                () -> new AnalysisContent("private", List.of(), null),
                () -> new AnalysisContent("private", List.of(), Arrays.asList((String) null)),
                () -> new AnalysisContent("private", List.of(), List.of("")),
                () -> new AnalysisContent.Finding(null, "private", List.of("e")),
                () -> new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "", List.of("e")),
                () -> new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "private", null),
                () -> new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "private", List.of()),
                () -> new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "private", List.of("")));
        cases.forEach(run -> assertThatThrownBy(run::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_ANALYSIS_CONTENT").hasNoCause());
    }

    @Test void retainsExistingEmptyCollectionAndWhitespaceSemantics() {
        assertThat(new AnalysisContent(" ", List.of(), List.of()).findings()).isEmpty();
        for (var kind : AnalysisContent.Kind.values()) {
            var finding = new AnalysisContent.Finding(kind, " ", List.of("e", "e"));
            assertThat(finding.kind()).isEqualTo(kind);
        }
    }

    @Test void usageAllowsExactLimitsWithoutInferringUnknownAsZero() {
        assertThat(new AnalysisUsage(4096, 1024, 5120, 4096, 0).totalTokens()).isEqualTo(5120);
        assertThat(new AnalysisUsage(1, 0, 1, 0, 1).completionTokens()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,0,0", "4097,0,4097,0,4097", "1,1025,1026,0,1", "1,-1,0,0,1",
            "1,0,2,0,1", "1,0,1,-1,2", "1,0,1,2,-1", "1,0,1,0,0",
            "1,0,1,2147483647,2147483647", "4096,1024,-2147483648,0,4096"})
    void usageRejectsInvalidTotalsAndOverflow(int prompt, int completion, int total, int hit, int miss) {
        assertThatThrownBy(() -> new AnalysisUsage(prompt, completion, total, hit, miss))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_ANALYSIS_USAGE").hasNoCause();
    }
}
