package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppUserDashboardGrant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** 终端用户看板授权响应闭集、字符串revision与撤销元数据投影测试。 */
class AppUserDashboardGrantResponseTests {

    /** ACTIVE响应固定READ且隐藏内部行、范围和操作者身份，revision不暴露Java Long。 */
    @Test
    void mapsActiveGrantToExactPublicFields() {
        UUID appUserId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-07T01:02:03Z");
        Instant updatedAt = Instant.parse("2026-09-07T02:03:04Z");
        AppUserDashboardGrant grant = grant(
                appUserId, dashboardId, AppUserDashboardGrant.Status.ACTIVE,
                Long.MAX_VALUE, createdAt, updatedAt, null, null);

        AppUserDashboardGrantResponse response = AppUserDashboardGrantResponse.from(grant);

        assertThat(response.appUserId()).isEqualTo(appUserId);
        assertThat(response.dashboardId()).isEqualTo(dashboardId);
        assertThat(response.permission()).isEqualTo("READ");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.revision()).isEqualTo("9223372036854775807");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(response.revokedAt()).isNull();
        assertThat(Arrays.stream(AppUserDashboardGrantResponse.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("appUserId", "dashboardId", "permission", "status", "revision",
                        "createdAt", "updatedAt", "revokedAt");
    }

    /** REVOKED只增加合同内撤销时刻，不向响应泄露撤销操作者或内部授权身份。 */
    @Test
    void mapsRevokedGrantWithRevocationTimestamp() {
        Instant changedAt = Instant.parse("2026-09-07T03:04:05Z");
        AppUserDashboardGrant grant = grant(
                UUID.randomUUID(), UUID.randomUUID(), AppUserDashboardGrant.Status.REVOKED,
                2, Instant.parse("2026-09-07T01:02:03Z"), changedAt, changedAt, UUID.randomUUID());

        AppUserDashboardGrantResponse response = AppUserDashboardGrantResponse.from(grant);

        assertThat(response.status()).isEqualTo("REVOKED");
        assertThat(response.revision()).isEqualTo("2");
        assertThat(response.revokedAt()).isEqualTo(changedAt);
    }

    /** 空领域事实属于调用方编排错误，不能投影出部分HTTP响应。 */
    @Test
    void rejectsMissingDomainGrant() {
        assertThatNullPointerException().isThrownBy(() -> AppUserDashboardGrantResponse.from(null));
    }

    /** 建立完整领域事实，内部ID、范围和操作者用于证明响应转换确实执行字段缩减。 */
    private static AppUserDashboardGrant grant(UUID appUserId, UUID dashboardId,
                                                AppUserDashboardGrant.Status status, long revision,
                                                Instant createdAt, Instant updatedAt,
                                                Instant revokedAt, UUID revokedBy) {
        UUID actorId = UUID.randomUUID();
        return new AppUserDashboardGrant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), appUserId, dashboardId,
                status, revision, createdAt, updatedAt, revokedAt, actorId, actorId, revokedBy);
    }
}
