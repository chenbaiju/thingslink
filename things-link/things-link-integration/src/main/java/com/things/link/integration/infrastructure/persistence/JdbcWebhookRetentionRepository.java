package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.WebhookRetentionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcWebhookRetentionRepository implements WebhookRetentionRepository {
    private final JdbcTemplate jdbc;public JdbcWebhookRetentionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Scope> candidates(){return jdbc.query("SELECT * FROM integ_webhook_retention_candidates(25)",(r,n)->new Scope(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));}
    public int purge(Scope scope,int limit){return jdbc.queryForObject("SELECT integ_webhook_purge_scope(?,?,?)",Integer.class,scope.tenant(),scope.project(),limit);}
}
