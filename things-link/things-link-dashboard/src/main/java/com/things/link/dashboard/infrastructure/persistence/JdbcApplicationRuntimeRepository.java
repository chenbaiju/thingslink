package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.ApplicationRuntimeLocation;
import com.things.link.dashboard.domain.ApplicationRuntimeRepository;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.domain.PublishedApplicationRuntimeProjection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL应用运行入口两阶段持久适配器。
 *
 * <p>首跳只调用固定SECURITY DEFINER函数，不能拼表名或附带客户端提供的tenant/project；第二跳依赖调用方
 * 已在同一事务连接建立的项目RLS，并再次按三身份、appKey、删除状态和当前版本指针做精确连接。</p>
 */
@Repository
public class JdbcApplicationRuntimeRepository implements ApplicationRuntimeRepository {

    /** 无范围首跳只能取得建立RLS所需的三项身份。 */
    private static final String LOCATE_SQL = """
            SELECT tenant_id, project_id, application_id
              FROM public.resolve_application_runtime_identity(?)
            """;

    /**
     * 普通RLS查询重新锁定全部身份与当前指针，displayName只从精确不可变版本快照投影。
     * formatVersion刻意不进入WHERE过滤，而由映射分支检查，避免损坏正文静默变成不存在。
     */
    private static final String FIND_CURRENT_SQL = """
            SELECT application.tenant_id,
                   application.project_id,
                   application.id AS application_id,
                   application.app_key,
                   version.snapshot ->> 'formatVersion' AS format_version,
                   jsonb_typeof(version.snapshot -> 'displayName') AS display_name_type,
                   version.snapshot ->> 'displayName' AS display_name,
                   version.snapshot_digest_algorithm,
                   version.snapshot_digest,
                   encode(digest(convert_to(version.snapshot::text, 'UTF8'), 'sha256'), 'hex')
                       AS calculated_snapshot_digest
              FROM public.app_application application
              JOIN public.app_application_version version
                ON version.tenant_id = application.tenant_id
               AND version.project_id = application.project_id
               AND version.application_id = application.id
               AND version.id = application.current_version_id
             WHERE application.tenant_id = ?
               AND application.project_id = ?
               AND application.id = ?
               AND application.app_key = ?
               AND application.deleted_at IS NULL
               AND application.current_version_id IS NOT NULL
            """;

    /** 已绑定调用方事务连接的JDBC入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建应用运行入口持久适配器。
     *
     * @param jdbcTemplate 事务感知JDBC访问器
     */
    public JdbcApplicationRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ApplicationRuntimeLocation> locateByAppKey(String appKey) {
        return jdbcTemplate.query(LOCATE_SQL, (result, row) -> new ApplicationRuntimeLocation(
                result.getObject("tenant_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("application_id", UUID.class)), appKey).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PublishedApplicationRuntimeProjection> findCurrent(
            UUID tenantId, UUID projectId, UUID applicationId, String appKey) {
        return jdbcTemplate.query(FIND_CURRENT_SQL, (result, row) -> {
            String formatVersion = result.getString("format_version");
            String displayNameType = result.getString("display_name_type");
            String displayName = result.getString("display_name");
            String digestAlgorithm = result.getString("snapshot_digest_algorithm");
            String storedDigest = result.getString("snapshot_digest");
            String calculatedDigest = result.getString("calculated_snapshot_digest");
            if (!ApplicationVersion.SNAPSHOT_DIGEST_ALGORITHM.equals(digestAlgorithm)
                    || storedDigest == null
                    || !storedDigest.equals(calculatedDigest)) {
                throw new DataIntegrityViolationException("当前应用版本快照摘要不一致");
            }
            if (!"tc.application/v1".equals(formatVersion)
                    || !"string".equals(displayNameType)
                    || displayName == null) {
                throw new DataIntegrityViolationException("当前应用版本快照缺少冻结格式或字符串公开展示名称");
            }
            return new PublishedApplicationRuntimeProjection(
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("project_id", UUID.class),
                    result.getObject("application_id", UUID.class),
                    result.getString("app_key"),
                    displayName);
        }, tenantId, projectId, applicationId, appKey).stream().findFirst();
    }
}
