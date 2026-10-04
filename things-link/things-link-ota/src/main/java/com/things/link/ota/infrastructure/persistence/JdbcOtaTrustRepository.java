package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaTrustRepository;
import com.things.link.ota.domain.OtaTrustState;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 当前域行锁与精确历史包绑定；缺历史显式失败，不把损坏映射当作不存在。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcOtaTrustRepository implements OtaTrustRepository {
    /** 调用方事务的RLS入口。 */
    private final JdbcTemplate jdbc;
    /** 只由本类选择锁模式，禁止客户端SQL片段。 */
    private static final String SELECT_CURRENT="""
            SELECT d.tenant_id,d.project_id,d.trust_domain,d.root_profile,d.root_fingerprint,
                d.policy_revision,d.policy_hash,d.revision,d.bundle_version,d.created_at,d.updated_at,
                b.bundle_sha256,b.canonical_bundle,b.signature,b.bundle_version AS resolved_bundle_version
              FROM ota_trust_domain d LEFT JOIN ota_trust_bundle b
                ON b.tenant_id=d.tenant_id AND b.project_id=d.project_id
                AND b.trust_domain=d.trust_domain AND b.bundle_version=d.bundle_version
            """;
    /** 注入当前物理事务的JDBC。 */
    public JdbcOtaTrustRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** {@inheritDoc} */
    @Override public void lockImport(UUID tenantId,UUID projectId,String trustDomain) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
                +"concat_ws(':','ota-trust-import-v1',?::text,?::text,?),13018::bigint))",
                Integer.class,tenantId,projectId,trustDomain);
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaTrustState> find(UUID projectId,String domain,boolean exclusive,boolean shared) {
        if(exclusive && shared)throw new IllegalArgumentException("信任域锁模式互斥");
        return jdbc.query(SELECT_CURRENT+" WHERE d.project_id=? AND d.trust_domain=?"
                        +(exclusive?" FOR UPDATE OF d":shared?" FOR SHARE OF d":""),
                JdbcOtaTrustRepository::map,projectId,domain).stream().findFirst();
    }
    /**
     * {@inheritDoc}
     *
     * <p>域名是全局主键，项目内因此天然唯一：单个{@code trust_domain}升序就是确定性全序，
     * 键集比较{@code d.trust_domain>?}不会漏读或重复，也不需要第二并列键。
     * 游标载荷绑定{@code project|trustDomain}，跨项目复用一律判为非法，不泄露其他项目的位置。
     * 查询不取任何锁、不写任何行，当前包字节只随结果返回给应用层做公开摘要投影。
     */
    @Override public CursorPage<OtaTrustState> domains(UUID projectId,String cursor,int limit) {
        if(limit<1||limit>100)throw invalidCursor();
        List<OtaTrustState> rows;
        if(cursor==null||cursor.isEmpty()) {
            rows=jdbc.query(SELECT_CURRENT+" WHERE d.project_id=? ORDER BY d.trust_domain ASC LIMIT ?",
                    JdbcOtaTrustRepository::map,projectId,limit+1);
        } else {
            String domain;
            try {
                if(cursor.length()>320)throw invalidCursor();
                String[] parts=Cursor.decode(cursor).split("\\|",-1);
                if(parts.length!=2||!projectId.toString().equals(parts[0])
                        ||!parts[1].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw invalidCursor();
                domain=parts[1];
            } catch (RuntimeException failure) { throw invalidCursor(); }
            rows=jdbc.query(SELECT_CURRENT+" WHERE d.project_id=? AND d.trust_domain>?"
                            +" ORDER BY d.trust_domain ASC LIMIT ?",
                    JdbcOtaTrustRepository::map,projectId,domain,limit+1);
        }
        if(rows.size()<=limit)return CursorPage.last(rows);
        List<OtaTrustState> items=List.copyOf(rows.subList(0,limit));
        OtaTrustState last=items.getLast();
        return CursorPage.of(items,Cursor.encode(projectId+"|"+last.trustDomain()));
    }
    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER,"信任域分页参数不合法");
    }
    /** 先域后历史，同一个MANDATORY事务提交；历史FK不会允许跨域孤儿。 */
    @Override public void create(OtaTrustState s) {
        jdbc.update("""
                INSERT INTO ota_trust_domain(tenant_id,project_id,trust_domain,root_profile,root_fingerprint,
                    policy_revision,policy_hash,revision,bundle_version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,s.tenantId(),s.projectId(),s.trustDomain(),s.rootProfile(),s.rootFingerprint(),
                s.policyRevision(),s.policyHash(),s.revision(),s.bundleVersion(),Timestamp.from(s.createdAt()),Timestamp.from(s.updatedAt()));
        insertBundle(s);
    }
    /** 锁定后核对CAS再插历史，正常false返回不会留下未采用版本。 */
    @Override public boolean replace(long expectedRevision,OtaTrustState s) {
        var current=find(s.projectId(),s.trustDomain(),true,false).orElse(null);
        if(current==null || current.revision()!=expectedRevision || !current.tenantId().equals(s.tenantId()))return false;
        if(!current.rootProfile().equals(s.rootProfile()) || !current.rootFingerprint().equals(s.rootFingerprint())
                || !current.createdAt().equals(s.createdAt()))
            throw new DataIntegrityViolationException("OTA信任根身份不可替换");
        insertBundle(s);
        return jdbc.update("""
                UPDATE ota_trust_domain SET policy_revision=?,policy_hash=?,revision=?,bundle_version=?,updated_at=?
                 WHERE tenant_id=? AND project_id=? AND trust_domain=? AND revision=?
                """,s.policyRevision(),s.policyHash(),s.revision(),s.bundleVersion(),Timestamp.from(s.updatedAt()),
                s.tenantId(),s.projectId(),s.trustDomain(),expectedRevision)==1;
    }
    /** 只追加不可变历史；外层事务回滚时与指针一起回滚。 */
    private void insertBundle(OtaTrustState s) {
        jdbc.update("""
                INSERT INTO ota_trust_bundle(tenant_id,project_id,trust_domain,bundle_version,bundle_sha256,
                    canonical_bundle,signature,created_at) VALUES (?,?,?,?,?,?,?,?)
                """,s.tenantId(),s.projectId(),s.trustDomain(),s.bundleVersion(),s.bundleSha256(),
                s.canonicalBundle(),s.signature(),Timestamp.from(s.updatedAt()));
    }
    /** 不使用INNER JOIN吞掉缺失映射，不验证签名的仓储不能产生密码学资格。 */
    private static OtaTrustState map(ResultSet rs,int row)throws SQLException {
        if(rs.getObject("resolved_bundle_version")==null || rs.getBytes("canonical_bundle")==null
                || rs.getBytes("signature")==null || rs.getString("bundle_sha256")==null)
            throw new DataIntegrityViolationException("OTA信任域缺少精确历史包");
        return new OtaTrustState(rs.getObject("tenant_id",UUID.class),rs.getObject("project_id",UUID.class),
                rs.getString("trust_domain"),rs.getString("root_profile"),rs.getString("root_fingerprint"),
                rs.getLong("policy_revision"),rs.getString("policy_hash"),rs.getLong("revision"),
                rs.getLong("bundle_version"),rs.getString("bundle_sha256"),rs.getBytes("canonical_bundle"),
                rs.getBytes("signature"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
}
