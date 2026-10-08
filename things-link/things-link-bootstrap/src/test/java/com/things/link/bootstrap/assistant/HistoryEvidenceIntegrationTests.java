package com.things.link.bootstrap.assistant;

import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.ConsoleHistoryEvidence;
import com.things.link.telemetry.application.ConsoleHistoryEvidenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class HistoryEvidenceIntegrationTests extends AbstractAssistantIntegrationTest {
    @Autowired ConsoleHistoryEvidenceService service;
    @Autowired JdbcTemplate application;
    JdbcTemplate owner;
    DataFixture data;
    Instant to, from;
    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        data = WebAppDataRuntimeFixture.seed(owner);
        var f = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                Uuid7.generate(), f.projectId(), f.actorId());
        TenantContext.set(new TenantScope(f.tenantId(), f.projectId(), f.actorId()));
        to = owner.queryForObject("SELECT statement_timestamp()", Timestamp.class).toInstant().minusSeconds(1);
        from = to.minusSeconds(3600);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    ConsoleHistoryEvidence read() {
        return service.read(data.runtime().projectId(), data.first(), data.model(), "temperature", from, to);
    }
    void point(Instant time, UUID source, Double value, String text) {
        owner.update("""
            INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,data_type,
                thing_model_version_id,model_version,value_double,value_text,quality)
            VALUES (?,?,'temperature',?,?,?,?,?,?,?,1)
            """, data.runtime().projectId(), data.first(), Timestamp.from(time), Uuid7.generate(),
                source == null ? null : text == null ? "NUMBER" : "TEXT", source,
                source == null ? null : "1.0.0", value, text);
    }
    @Test void readsForAllFourRolesWithRealAppRlsAndVersionedFacts() {
        point(from.plusSeconds(1), data.model(), 12.5, null);
        point(from.plusSeconds(2), null, 99.0, null);
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR", "VIEWER")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",
                    role, data.runtime().projectId(), data.runtime().actorId());
            var result = read();
            assertThat(result.state()).isEqualTo(ConsoleHistoryEvidence.State.HAS_POINTS);
            assertThat(result.points()).hasSize(2);
            assertThat(result.points().getFirst().value()).isEqualTo(12.5);
            assertThat(result.points().getLast().source()).isEqualTo(ConsoleHistoryEvidence.Source.SOURCE_UNKNOWN);
            assertThat(result.points().getLast().value()).isNull();
            assertThat(result.requestedFrom()).isEqualTo(from); assertThat(result.requestedTo()).isEqualTo(to);
        }
    }
    @Test void revocationAndWrongProjectNeverReturnPreviouslyVisibleHistory() {
        point(from.plusSeconds(1), data.model(), 12.5, null);
        assertThat(read().points()).hasSize(1);
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), data.first(), data.model(), "temperature", from, to))
                .isInstanceOf(BusinessException.class);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",
                data.runtime().projectId(), data.runtime().actorId());
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);
    }
    @Test void emptyRetentionAndFutureWindowsHaveDistinctOutcomes() {
        assertThat(read().state()).isEqualTo(ConsoleHistoryEvidence.State.NO_POINTS);
        var old = service.read(data.runtime().projectId(), data.first(), data.model(), "temperature",
                to.minusSeconds(11 * 86400), to.minusSeconds(10 * 86400));
        assertThat(old.state()).isEqualTo(ConsoleHistoryEvidence.State.OUTSIDE_RETENTION);
        assertThat(old.retentionClipped()).isTrue();
        assertThatThrownBy(() -> service.read(data.runtime().projectId(), data.first(), data.model(), "temperature",
                to, to.plusSeconds(86400))).isInstanceOf(BusinessException.class);
    }
    @Test void exactModelUnknownPropertiesAndNonNumericWindowReject() {
        assertThatThrownBy(() -> service.read(data.runtime().projectId(), data.first(), UUID.randomUUID(),
                "temperature", from, to)).isInstanceOf(BusinessException.class);
        for (String key : List.of("unknown", "secret")) {
            assertThatThrownBy(() -> service.read(data.runtime().projectId(), data.first(), data.model(), key, from, to))
                    .isInstanceOf(BusinessException.class);
        }
        point(from.plusSeconds(1), data.model(), null, "must-not-be-sent");
        assertThatThrownBy(this::read).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(30058));
    }
    @Test void crossTenantCollaboratorUsesProjectTenantButCannotReadAnotherProjectsDevice() {
        var foreign = WebAppDataRuntimeFixture.seed(owner);
        var f = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                Uuid7.generate(), f.projectId(), foreign.runtime().actorId());
        TenantContext.set(new TenantScope(foreign.runtime().tenantId(), f.projectId(), foreign.runtime().actorId()));
        point(from.plusSeconds(1), data.model(), 12.5, null);
        assertThat(read().points()).hasSize(1);
        assertThatThrownBy(() -> service.read(f.projectId(), foreign.first(), foreign.model(), "temperature", from, to))
                .isInstanceOf(BusinessException.class);
    }
}
