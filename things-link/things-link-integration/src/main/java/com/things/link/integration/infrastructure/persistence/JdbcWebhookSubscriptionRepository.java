package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.*;
import com.things.link.shared.error.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcWebhookSubscriptionRepository implements WebhookSubscriptionRepository {
    private final JdbcTemplate jdbc;
    public JdbcWebhookSubscriptionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String SELECT="SELECT s.*,r.name,r.target_url,r.event_types,r.device_ids,r.authorized_by,r.signing_key_id,r.signing_generation FROM integ_webhook_subscription s JOIN integ_webhook_revision r ON (r.tenant_id,r.project_id,r.subscription_id,r.revision)=(s.tenant_id,s.project_id,s.id,s.current_revision) ";
    public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    public Optional<WebhookSubscription> find(UUID id){return jdbc.query(SELECT+"WHERE s.id=?",(r,n)->map(r),id).stream().findFirst();}
    public Optional<WebhookSubscription> lockForDelivery(UUID id){return jdbc.query(SELECT+"WHERE s.id=? FOR SHARE OF s",(r,n)->map(r),id).stream().findFirst();}
    public List<WebhookSubscription> page(UUID project,UUID after,int limit){return jdbc.query(SELECT+"WHERE s.project_id=? AND (?::uuid IS NULL OR s.id>?::uuid) ORDER BY s.id LIMIT ?",(r,n)->map(r),project,after,after,Math.min(101,limit));}
    public Optional<WebhookOperation> operation(UUID id){return jdbc.query("SELECT * FROM integ_webhook_operation WHERE operation_id=?",(r,n)->new WebhookOperation(r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),id,r.getObject("actor_account_id",UUID.class),r.getString("kind"),r.getString("request_digest"),r.getObject("result_id",UUID.class),r.getLong("result_revision"),r.getTimestamp("completed_at").toInstant()),id).stream().findFirst();}
    public void insert(WebhookSubscription t){try{jdbc.update("INSERT INTO integ_webhook_subscription VALUES (?,?,?,?,?,?,?,?,?)",t.id(),t.tenant(),t.project(),t.generation(),t.createdBy(),Timestamp.from(t.createdAt()),t.revision(),t.status(),Timestamp.from(t.updatedAt()));}catch(DataAccessException ex){throw classify(ex);}revision(t);}
    public void revise(WebhookSubscription t,long expected){try{if(jdbc.update("UPDATE integ_webhook_subscription SET current_revision=?,status=?,updated_at=? WHERE id=? AND current_revision=? AND status<>'REVOKED'",t.revision(),t.status(),Timestamp.from(t.updatedAt()),t.id(),expected)!=1)throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);}catch(DataAccessException ex){throw classify(ex);}revision(t);}
    private void revision(WebhookSubscription t){jdbc.update("INSERT INTO integ_webhook_revision VALUES (?,?,?,?,?,?,?::text[],?::uuid[],?,?,?,?)",t.tenant(),t.project(),t.id(),t.revision(),t.name(),t.target(),array(t.eventTypes()),array(t.deviceIds()),t.authorizedBy(),Timestamp.from(t.updatedAt()),t.signingKeyId(),t.signingGeneration());}
    public void complete(WebhookOperation o){jdbc.update("INSERT INTO integ_webhook_operation VALUES (?,?,?,?,?,?,?,?,?)",o.tenant(),o.project(),o.operation(),o.actor(),o.kind(),o.digest(),o.result(),o.revision(),Timestamp.from(o.completedAt()));}
    private static RuntimeException classify(DataAccessException ex){for(Throwable c=ex;c!=null;c=c.getCause())if(c instanceof SQLException sql&&"P0001".equals(sql.getSQLState())&&sql.getMessage().contains("INTEG_WEBHOOK_CAPACITY"))return new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);return ex;}
    private static String array(List<?> values){return "{"+String.join(",",values.stream().map(Object::toString).toList())+"}";}
    private static WebhookSubscription map(ResultSet r)throws SQLException{return new WebhookSubscription(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),r.getLong("project_generation"),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant(),r.getLong("current_revision"),r.getString("status"),r.getTimestamp("updated_at").toInstant(),r.getString("name"),r.getString("target_url"),Arrays.asList((String[])r.getArray("event_types").getArray()),Arrays.stream((Object[])r.getArray("device_ids").getArray()).map(v->UUID.fromString(v.toString())).toList(),r.getObject("authorized_by",UUID.class),r.getString("signing_key_id"),r.getObject("signing_generation",UUID.class));}
}
