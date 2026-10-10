package com.things.link.enduser.infrastructure.persistence;
import com.things.link.enduser.domain.AppNotificationContact;
import com.things.link.enduser.domain.AppNotificationContactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** 三轴显式条件与项目RLS保护接收号码。 */
@Repository
public class JdbcAppNotificationContactRepository implements AppNotificationContactRepository {
    private final JdbcTemplate jdbc;
    /** @param jdbc 项目上下文数据源入口 */
    public JdbcAppNotificationContactRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override
    public AppNotificationContact read(UUID tenantId, UUID projectId, UUID userId) {
        return jdbc.query("SELECT voice_number,sms_number,revision FROM app_project_notification_contact WHERE tenant_id=? AND project_id=? AND app_user_id=?",
                (rs,row) -> new AppNotificationContact(rs.getString(1),rs.getString(2),rs.getLong(3)),tenantId,projectId,userId)
                .stream().findFirst().orElse(new AppNotificationContact(null,null,0));
    }
    /** {@inheritDoc} */
    @Override
    public void save(UUID tenantId, UUID projectId, UUID userId, AppNotificationContact contact) {
        jdbc.update("""
                INSERT INTO app_project_notification_contact(tenant_id,project_id,app_user_id,voice_number,sms_number,revision)
                VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,project_id,app_user_id) DO UPDATE SET
                voice_number=EXCLUDED.voice_number,sms_number=EXCLUDED.sms_number,revision=EXCLUDED.revision,updated_at=now()
                """,tenantId,projectId,userId,contact.voiceNumber(),contact.smsNumber(),contact.revision());
    }
}
