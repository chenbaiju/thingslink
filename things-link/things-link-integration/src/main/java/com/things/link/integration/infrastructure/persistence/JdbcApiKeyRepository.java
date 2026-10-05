package com.things.link.integration.infrastructure.persistence;

import com.things.link.integration.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 只访问integ事实；项目授权与锁来自公开项目端口。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcApiKeyRepository implements ApiKeyRepository {
    private final JdbcTemplate jdbc;
    public JdbcApiKeyRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    @Override public List<String> canonicalCidrs(List<String> cidrs) {
        return jdbc.queryForList("SELECT DISTINCT x::text FROM unnest(?::cidr[]) x ORDER BY 1",String.class,array(cidrs));
    }
    @Override public Optional<ApiKeyFact> lockForDelivery(UUID tenant,UUID project,UUID id){
        return jdbc.query("SELECT * FROM integ_api_key WHERE tenant_id=? AND project_id=? AND id=? FOR SHARE",(r,n)->map(r),tenant,project,id).stream().findFirst();
    }
    @Override public Optional<ApiKeyFact> find(UUID tenant,UUID project,UUID id) {
        return jdbc.query("SELECT * FROM integ_api_key WHERE tenant_id=? AND project_id=? AND id=?",
                (rs,n)->map(rs),tenant,project,id).stream().findFirst();
    }
    @Override public Optional<ApiKeyOperation> operation(UUID tenant,UUID project,UUID operation) {
        return jdbc.query("SELECT * FROM integ_api_key_operation WHERE tenant_id=? AND project_id=? AND operation_id=?",
                (r,n)->new ApiKeyOperation(tenant,project,operation,r.getObject("actor_account_id",UUID.class),
                        r.getString("kind"),r.getString("request_digest"),r.getObject("target_id",UUID.class),
                        r.getObject("result_key_id",UUID.class),r.getTimestamp("completed_at").toInstant()),tenant,project,operation).stream().findFirst();
    }
    @Override public int activeCount(UUID tenant,UUID project,Instant now) {
        return jdbc.queryForObject("SELECT count(*) FROM integ_api_key WHERE tenant_id=? AND project_id=? AND status='ACTIVE' AND expires_at>?",
                Integer.class,tenant,project,Timestamp.from(now));
    }
    @Override public List<ApiKeyFact> page(UUID tenant,UUID project,UUID after,int limit) {
        return jdbc.query("SELECT * FROM integ_api_key WHERE tenant_id=? AND project_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
                (r,n)->map(r),tenant,project,after,after,limit);
    }
    @Override public void insert(ApiKeyFact k) {
        jdbc.update("""
                INSERT INTO integ_api_key(id,tenant_id,project_id,project_generation,issuer_account_id,name,
                    secret_hash,scopes,ip_cidrs,status,created_at,expires_at,revision)
                VALUES (?,?,?,?,?,?,?,?::text[],?::cidr[],'ACTIVE',?,?,0)
                """,k.id(),k.tenantId(),k.projectId(),k.generation(),k.issuer(),k.name(),k.secretHash(),
                array(k.scopes()),array(k.cidrs()),Timestamp.from(k.createdAt()),Timestamp.from(k.expiresAt()));
    }
    @Override public void revoke(UUID tenant,UUID project,UUID id,Instant now) {
        jdbc.update("UPDATE integ_api_key SET status='REVOKED',revoked_at=?,revision=revision+1 WHERE tenant_id=? AND project_id=? AND id=? AND status='ACTIVE'",
                Timestamp.from(now),tenant,project,id);
    }
    @Override public void complete(ApiKeyOperation o) {
        jdbc.update("INSERT INTO integ_api_key_operation VALUES (?,?,?,?,?,?,?,?,?)",o.tenant(),o.project(),o.operation(),
                o.actor(),o.kind(),o.requestDigest(),o.target(),o.result(),Timestamp.from(o.completedAt()));
    }
    private static String array(List<String> values) {
        // 执行 SQL 前，调用方须校验封闭范围字符集以及 IP 字面量或 CIDR 字符集。
        return "{"+String.join(",",values)+"}";
    }
    private static ApiKeyFact map(ResultSet r) throws SQLException {
        Timestamp revoked=r.getTimestamp("revoked_at");
        return new ApiKeyFact(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("project_id",UUID.class),
                r.getLong("project_generation"),r.getObject("issuer_account_id",UUID.class),r.getString("name"),r.getString("secret_hash"),
                Arrays.asList((String[])r.getArray("scopes").getArray()),
                Arrays.stream((Object[])r.getArray("ip_cidrs").getArray()).map(Object::toString).toList(),r.getString("status"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),revoked==null?null:revoked.toInstant(),r.getLong("revision"));
    }
}
