package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.ProjectInvitation;
import com.things.link.project.domain.ProjectInvitationRepository;
import com.things.link.shared.authz.ProjectRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 邀请身份目录；仅显式项目/收件邮箱分页，不存在全平台列表端口。 */
@Repository
public class JdbcProjectInvitationRepository implements ProjectInvitationRepository {
    private final JdbcTemplate jdbc;
    public JdbcProjectInvitationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<ProjectInvitation> ROW = (rs, n) -> new ProjectInvitation(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
            rs.getObject("inviter_account_id", UUID.class), rs.getString("target_email"), ProjectRole.valueOf(rs.getString("role")),
            ProjectInvitation.Status.valueOf(rs.getString("status")), rs.getLong("revision"), rs.getObject("code_nonce", UUID.class),
            rs.getTimestamp("expires_at").toInstant(), ProjectInvitation.Channel.valueOf(rs.getString("delivery_channel")),
            ProjectInvitation.Delivery.valueOf(rs.getString("delivery_status")), rs.getObject("accepted_account_id", UUID.class),
            rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    @Override public Optional<ProjectInvitation> find(UUID id) {
        return jdbc.query("SELECT * FROM sys_project_invitation WHERE id=?", ROW, id).stream().findFirst();
    }
    @Override public Optional<ProjectInvitation> lock(UUID id, UUID projectId) {
        return jdbc.query("SELECT * FROM sys_project_invitation WHERE id=? AND project_id=? FOR UPDATE", ROW, id, projectId).stream().findFirst();
    }
    @Override public void create(ProjectInvitation i) {
        jdbc.update("""
                INSERT INTO sys_project_invitation(id,tenant_id,project_id,inviter_account_id,target_email,role,status,
                    revision,code_nonce,expires_at,delivery_channel,delivery_status,accepted_account_id,accepted_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, i.id(), i.tenantId(), i.projectId(), i.inviterAccountId(), i.targetEmail(), i.role().name(), i.status().name(),
                i.revision(), i.codeNonce(), time(i.expiresAt()), i.deliveryChannel().name(), i.deliveryStatus().name(),
                i.acceptedAccountId(), time(i.acceptedAt()), time(i.createdAt()), time(i.updatedAt()));
    }
    @Override public boolean replace(ProjectInvitation i, long revision) {
        return jdbc.update("""
                UPDATE sys_project_invitation SET inviter_account_id=?,status=?,revision=?,code_nonce=?,expires_at=?,
                    delivery_channel=?,delivery_status=?,accepted_account_id=?,accepted_at=?,updated_at=?
                WHERE id=? AND project_id=? AND revision=? AND status IN ('PENDING','EXPIRED')
                """, i.inviterAccountId(), i.status().name(), i.revision(), i.codeNonce(), time(i.expiresAt()),
                i.deliveryChannel().name(), i.deliveryStatus().name(), i.acceptedAccountId(), time(i.acceptedAt()),
                time(i.updatedAt()), i.id(), i.projectId(), revision) == 1;
    }
    @Override public void expirePending(UUID project, String email, Instant now) {
        jdbc.update("UPDATE sys_project_invitation SET status='EXPIRED',updated_at=? "
                + "WHERE project_id=? AND target_email=? AND status='PENDING' AND expires_at<=?", time(now), project, email, time(now));
    }
    @Override public List<ProjectInvitation> projectPage(UUID project, Instant before, UUID beforeId, int limit) {
        bounded(limit);
        return jdbc.query("SELECT * FROM sys_project_invitation WHERE project_id=? "
                + "AND (?::timestamptz IS NULL OR (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT ?",
                ROW, project, time(before), time(before), beforeId, limit);
    }
    @Override public List<ProjectInvitation> inboxPage(String email, Instant before, UUID beforeId, int limit) {
        bounded(limit);
        return jdbc.query("SELECT * FROM sys_project_invitation WHERE target_email=? "
                + "AND (?::timestamptz IS NULL OR (created_at,id)<(?::timestamptz,?::uuid)) ORDER BY created_at DESC,id DESC LIMIT ?",
                ROW, email, time(before), time(before), beforeId, limit);
    }
    @Override public boolean recordDelivery(UUID id, long revision, ProjectInvitation.Delivery delivery) {
        if (delivery != ProjectInvitation.Delivery.SENT && delivery != ProjectInvitation.Delivery.FAILED)
            throw new IllegalArgumentException("非法投递结果");
        return jdbc.update("UPDATE sys_project_invitation SET delivery_status=? "
                + "WHERE id=? AND revision=? AND status='PENDING' AND delivery_status='QUEUED'",
                delivery.name(), id, revision) == 1;
    }
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static void bounded(int limit) { if (limit < 1 || limit > 51) throw new IllegalArgumentException("邀请分页超限"); }
}
