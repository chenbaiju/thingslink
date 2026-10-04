package com.things.link.project.infrastructure.security;

import com.things.link.project.domain.ProjectInvitation;
import com.things.link.shared.authz.ProjectRole;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class HmacProjectInvitationCodesTests {
    private final HmacProjectInvitationCodes codes = new HmacProjectInvitationCodes("test-only-invitation-signing-secret-with-32-bytes");
    private ProjectInvitation invitation(long revision, String email, UUID project, ProjectRole role) {
        return new ProjectInvitation(new UUID(0,1), new UUID(0,2), project, new UUID(0,3), email, role,
                ProjectInvitation.Status.PENDING, revision, new UUID(0,4), Instant.parse("2026-10-07T00:00:00Z"),
                ProjectInvitation.Channel.EMAIL, ProjectInvitation.Delivery.QUEUED, null, null,
                Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
    }
    @Test void bindsRecipientProjectRoleAndRevisionAndSurvivesIdenticalReconstruction() {
        UUID project = new UUID(0,5);
        var original = invitation(1, "target@example.test", project, ProjectRole.VIEWER);
        String token = codes.issue(original);
        assertThat(token).hasSize(43).doesNotContain("target", "example");
        assertThat(codes.matches(invitation(1, "target@example.test", project, ProjectRole.VIEWER), token)).isTrue();
        assertThat(codes.matches(invitation(2, "target@example.test", project, ProjectRole.VIEWER), token)).isFalse();
        assertThat(codes.matches(invitation(1, "other@example.test", project, ProjectRole.VIEWER), token)).isFalse();
        assertThat(codes.matches(invitation(1, "target@example.test", UUID.randomUUID(), ProjectRole.VIEWER), token)).isFalse();
        assertThat(codes.matches(invitation(1, "target@example.test", project, ProjectRole.ADMIN), token)).isFalse();
        assertThat(new HmacProjectInvitationCodes("rotated-test-only-signing-secret-with-32-bytes").matches(original, token)).isFalse();
    }
    @Test void rejectsMissingPaddedAndTamperedCodesAndWeakConfiguration() {
        var value = invitation(1, "target@example.test", UUID.randomUUID(), ProjectRole.VIEWER);
        String token = codes.issue(value);
        for (String wrong : new String[]{null, "", token + "=", token.substring(1), "a".repeat(43), token + "\n"})
            assertThat(codes.matches(value, wrong)).isFalse();
        assertThatThrownBy(() -> new HmacProjectInvitationCodes("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
