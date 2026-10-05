package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.*;
/** 人工恢复回执 JDBC 仓储，持久保存操作身份及恢复结果。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcWebhookRecoveryRepository implements WebhookRecoveryRepository {
    private final JdbcTemplate jdbc;
    public JdbcWebhookRecoveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<WebhookRecoveryOperation> find(UUID operation){return jdbc.query("SELECT * FROM integ_webhook_recovery_operation WHERE operation_id=?",(r,n)->new WebhookRecoveryOperation(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),operation,r.getObject("actor_account_id",UUID.class),r.getString("request_digest"),r.getObject("delivery_id",UUID.class),r.getInt("result_round"),r.getTimestamp("completed_at").toInstant()),operation).stream().findFirst();}
    public void insert(WebhookRecoveryOperation o){jdbc.update("INSERT INTO integ_webhook_recovery_operation(tenant_id,project_id,operation_id,actor_account_id,request_digest,delivery_id,result_round,completed_at) VALUES (?,?,?,?,?,?,?,?)",o.tenant(),o.project(),o.operation(),o.actor(),o.digest(),o.delivery(),o.resultRound(),Timestamp.from(o.completedAt()));}
}
