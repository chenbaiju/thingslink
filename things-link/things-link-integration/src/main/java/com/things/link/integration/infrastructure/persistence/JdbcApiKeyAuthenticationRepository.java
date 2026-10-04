package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.ApiKeyAuthenticationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** ADR0171受控点查，无跨域SQL、无凭据日志或摘要输出。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class JdbcApiKeyAuthenticationRepository implements ApiKeyAuthenticationRepository {
    private final JdbcTemplate jdbc;
    public JdbcApiKeyAuthenticationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<Proof> prove(UUID id,String digest,String source){
        return jdbc.query("SELECT * FROM integ_authenticate_api_key(?,?,?::inet)",
                (r,n)->new Proof(r.getObject("key_id",UUID.class),r.getObject("tenant_id",UUID.class),
                        r.getObject("project_id",UUID.class),r.getLong("project_generation"),
                        r.getObject("issuer_account_id",UUID.class),Arrays.asList((String[])r.getArray("scopes").getArray()),
                        r.getTimestamp("expires_at").toInstant()),id,digest,source).stream().findFirst();
    }
}
