package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaTypeBaselineRepository;
import com.things.link.ota.domain.OtaTypeBaselineState;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 头与历史同事务持久；缺失或摘要损坏不会退化为首次登记。 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcOtaTypeBaselineRepository implements OtaTypeBaselineRepository {
    /** 当前事务RLS入口。 */ private final JdbcTemplate jdbc;
    /** 锁限定当前头，历史精确版本不可被替代。 */
    private static final String SELECT_CURRENT="""
            SELECT d.tenant_id,d.project_id,d.device_type_id,d.revision,d.baseline_version,d.baseline_hash,
                d.created_at,d.updated_at,v.canonical,v.baseline_version AS resolved_version,
                v.baseline_hash AS resolved_hash,encode(sha256(v.canonical),'hex') AS actual_hash
            FROM ota_type_baseline d LEFT JOIN ota_type_baseline_version v
                ON v.tenant_id=d.tenant_id AND v.project_id=d.project_id AND v.device_type_id=d.device_type_id
                AND v.baseline_version=d.baseline_version
            WHERE d.project_id=? AND d.device_type_id=?
            """;
    /** 注入事务绑定连接。 */
    public JdbcOtaTypeBaselineRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void lockRegistration(UUID tenant,UUID project,UUID type) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(concat_ws(':','ota-baseline-v1',?::text,?::text,?::text),13022::bigint))",Integer.class,tenant,project,type);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaTypeBaselineState> find(UUID project,UUID type,boolean exclusive,boolean shared) {
        if(exclusive&&shared)throw new IllegalArgumentException("基线锁模式互斥");
        return jdbc.query(SELECT_CURRENT+(exclusive?" FOR UPDATE OF d":shared?" FOR SHARE OF d":""),JdbcOtaTypeBaselineRepository::map,project,type).stream().findFirst();
    }
    /** 身份从同一规范字节提取，不接受第二组可分裂参数。 */
    @Override public void create(OtaTypeBaselineState s) {
        jdbc.update("""
                WITH incoming AS (SELECT convert_from(?::bytea,'UTF8')::jsonb AS j)
                INSERT INTO ota_type_baseline(tenant_id,project_id,device_type_id,revision,baseline_version,baseline_hash,
                    product_key,trust_domain,root_fingerprint,hardware_model,created_at,updated_at)
                SELECT ?,?,?,?,?,?,j->>'productKey',j->>'trustDomain',j->>'rootFingerprint',j#>>'{hardware,model}',?,? FROM incoming
                """,s.canonical(),s.tenantId(),s.projectId(),s.deviceTypeId(),s.revision(),s.baselineVersion(),s.baselineHash(),Timestamp.from(s.createdAt()),Timestamp.from(s.updatedAt()));
        history(s);
    }
    /** 行锁后再CAS，任何后续失败抛错保持整体回滚。 */
    @Override public boolean replace(long expectedRevision,OtaTypeBaselineState s) {
        var previous=find(s.projectId(),s.deviceTypeId(),true,false).orElse(null);
        if(previous==null || previous.revision()!=expectedRevision || !previous.tenantId().equals(s.tenantId()))return false;
        if(!previous.createdAt().equals(s.createdAt()))throw new DataIntegrityViolationException("基线创建时间不可修改");
        history(s);
        int changed=jdbc.update("UPDATE ota_type_baseline SET revision=?,baseline_version=?,baseline_hash=?,updated_at=? WHERE tenant_id=? AND project_id=? AND device_type_id=? AND revision=?",
                s.revision(),s.baselineVersion(),s.baselineHash(),Timestamp.from(s.updatedAt()),s.tenantId(),s.projectId(),s.deviceTypeId(),expectedRevision);
        if(changed!=1)throw new DataIntegrityViolationException("基线锁内CAS失败");
        return true;
    }
    /** 原事务追加历史，不允许只留下未采用新版。 */
    private void history(OtaTypeBaselineState s) {
        jdbc.update("INSERT INTO ota_type_baseline_version(tenant_id,project_id,device_type_id,baseline_version,baseline_hash,canonical,created_at) VALUES (?,?,?,?,?,?,?)",
                s.tenantId(),s.projectId(),s.deviceTypeId(),s.baselineVersion(),s.baselineHash(),s.canonical(),Timestamp.from(s.updatedAt()));
    }
    /**
     * {@inheritDoc}
     *
     * <p>{@code ota_type_baseline_version}有真实的{@code created_at}（登记时刻，头守卫保证按版本非递减），
     * 但登记可能在同一个微秒内连发两版，因此仅凭时间不是全序：必须用同类型内严格单调的
     * {@code baseline_version}做确定性并列键。排序与键集比较都取
     * {@code (created_at,baseline_version)}这一真正唯一的组合，{@code LIMIT limit+1}决定是否续页。
     *
     * <p>游标载荷绑定{@code project|deviceType|createdAt|baselineVersion}：跨项目或跨类型复用
     * 一律判为非法，不泄露其他类型的历史位置。历史没有独立{@code id}列，其真实键就是
     * {@code (device_type_id,baseline_version)}，载荷末段因此写版本而不是UUID。
     *
     * <p>查询<b>不</b>SELECT{@code canonical}：历史列表只需要版本、摘要与时刻，能力配置在
     * 数据访问层就不进入响应链路（见{@link OtaTypeBaselineRepository.Version}）。
     * 摘要与规范字节的一致性由{@code ota_type_baseline_version_content_ck}与
     * {@code ota_type_baseline_version_guard_trg}在写入时保证，且{@code thingslink_app}
     * 对该表只有SELECT/INSERT，没有可绕过摘要的UPDATE路径，因此列表读取无需重复哈希整篇正文。
     */
    @Override public CursorPage<Version> versions(UUID project,UUID type,String cursor,int limit) {
        if (limit<1||limit>100) throw invalidCursor();
        List<Version> rows;
        if (cursor==null||cursor.isEmpty()) {
            rows=jdbc.query("SELECT baseline_version,baseline_hash,created_at FROM ota_type_baseline_version"
                    +" WHERE project_id=? AND device_type_id=?"
                    +" ORDER BY created_at DESC,baseline_version DESC LIMIT ?",
                    JdbcOtaTypeBaselineRepository::mapVersion,project,type,limit+1);
        } else {
            Instant time; long version;
            try {
                if (cursor.length()>320) throw invalidCursor();
                String[] parts=Cursor.decode(cursor).split("\\|",-1);
                if (parts.length!=4||!project.toString().equals(parts[0])
                        ||!type.toString().equals(parts[1])) throw invalidCursor();
                time=Instant.parse(parts[2]);
                version=Long.parseLong(parts[3]);
            } catch (RuntimeException failure) { throw invalidCursor(); }
            rows=jdbc.query("SELECT baseline_version,baseline_hash,created_at FROM ota_type_baseline_version"
                    +" WHERE project_id=? AND device_type_id=?"
                    +" AND (created_at,baseline_version)<(?,?)"
                    +" ORDER BY created_at DESC,baseline_version DESC LIMIT ?",
                    JdbcOtaTypeBaselineRepository::mapVersion,project,type,Timestamp.from(time),version,limit+1);
        }
        if (rows.size()<=limit) return CursorPage.last(rows);
        List<Version> items=List.copyOf(rows.subList(0,limit));
        Version last=items.getLast();
        return CursorPage.of(items,Cursor.encode(project+"|"+type+"|"+last.createdAt()+"|"+last.baselineVersion()));
    }
    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER,"类型基线版本分页参数不合法");
    }
    /** 只投影可公开的三列，规范字节不进入历史响应链路。 */
    private static Version mapVersion(ResultSet rs,int row)throws SQLException {
        String hash=rs.getString("baseline_hash");
        if(hash==null)throw new DataIntegrityViolationException("类型基线历史缺少规范摘要");
        return new Version(rs.getLong("baseline_version"),hash,rs.getTimestamp("created_at").toInstant());
    }
    /** 显式比对指针摘要与真实字节，不以内连接隐藏损坏。 */
    private static OtaTypeBaselineState map(ResultSet rs,int row)throws SQLException {
        String hash=rs.getString("baseline_hash");
        if(rs.getObject("resolved_version")==null || hash==null || !hash.equals(rs.getString("resolved_hash"))
                || !hash.equals(rs.getString("actual_hash")) || rs.getBytes("canonical")==null)
            throw new DataIntegrityViolationException("基线缺失精确历史或摘要损坏");
        return new OtaTypeBaselineState(rs.getObject("tenant_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("device_type_id",UUID.class),
                rs.getLong("revision"),rs.getLong("baseline_version"),hash,rs.getBytes("canonical"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
}
