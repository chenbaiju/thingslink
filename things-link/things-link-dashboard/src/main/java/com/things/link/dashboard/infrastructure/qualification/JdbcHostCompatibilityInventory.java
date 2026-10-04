package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardHostCompatibility;
import com.things.link.dashboard.application.publication.DashboardHostCompatibilityReport;
import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualification;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;

/**
 * 平台operator显式连接的只读全库预检；不是Spring运行角色组件，不接受项目RLS过滤后的空集冒充全局事实。
 * 事务及连接由调用方拥有，方法既不提交也不回滚，排他锁保持至调用方结束事务。
 */
public final class JdbcHostCompatibilityInventory {
    private static final int MAX_RECORDS = 100000;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final int maxRecords;
    private final long maxBytes;
    private final int maxExamples;

    /** 生产固定预算，不能由待检元数据扩大。 */
    public JdbcHostCompatibilityInventory() { this(MAX_RECORDS, MAX_BYTES, 100); }

    /** 包内测试只允许收紧预算，不能扩大生产上限。 */
    JdbcHostCompatibilityInventory(int maxRecords, long maxBytes, int maxExamples) {
        if (maxRecords < 1 || maxRecords > MAX_RECORDS || maxBytes < 1 || maxBytes > MAX_BYTES
                || maxExamples < 1 || maxExamples > 100) throw new IllegalArgumentException("预检预算无效");
        this.maxRecords = maxRecords; this.maxBytes = maxBytes; this.maxExamples = maxExamples;
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 只投影不可变需求，不查询应用名称、Schema正文或分享secret_hash。 */
    private static final String INVENTORY = """
            SELECT 'APPLICATION' AS kind, a.id,
              jsonb_build_object('format',v.snapshot->'formatVersion','hostCompatibility',v.snapshot->'hostCompatibility',
                'schemas',v.snapshot->'requiredSchemas','components',v.snapshot->'requiredComponents',
                'resources',v.snapshot->'requiredResources')::text AS requirements
            FROM public.app_application a LEFT JOIN public.app_application_version v
              ON (v.tenant_id,v.project_id,v.application_id,v.id)=(a.tenant_id,a.project_id,a.id,a.current_version_id)
            WHERE a.deleted_at IS NULL AND a.current_version_id IS NOT NULL
            UNION ALL
            SELECT 'DASHBOARD', d.id,
              jsonb_build_object('format',v.schema_version,'hostCompatibility',NULL,
                'schemas',jsonb_build_array(v.schema_version),'components',v.required_components,'resources',v.required_resources)::text
            FROM public.dash_dashboard d LEFT JOIN public.dash_dashboard_version v
              ON (v.tenant_id,v.project_id,v.dashboard_id,v.id)=(d.tenant_id,d.project_id,d.id,d.current_version_id)
            WHERE d.deleted_at IS NULL AND d.current_version_id IS NOT NULL
            UNION ALL
            SELECT 'SHARE', s.id,
              jsonb_build_object('format',v.schema_version,'hostCompatibility',s.host_compatibility,
                'schemas',jsonb_build_array(v.schema_version),'components',v.required_components,'resources',v.required_resources)::text
            FROM public.dash_share_token s LEFT JOIN public.dash_dashboard_version v
              ON (v.tenant_id,v.project_id,v.dashboard_id,v.id)=(s.tenant_id,s.project_id,s.dashboard_id,s.dashboard_version_id)
            WHERE s.revoked_at IS NULL AND s.expires_at>statement_timestamp()
            """;

    /**
     * 取得数据库排他围栏后完整扫描；超预算/非operator/无事务直接失败，永不部分扫描后返回PASS。
     * @param connection 显式具备全项目可见性的READ COMMITTED事务连接
     * @param target 已经完整复验的目标；真正切换前还必须复验实际制品
     * @return 有界诊断及全量计数
     * @throws SQLException 数据库不可用或锁/查询超时，调用方不得将异常当兼容
     */
    public DashboardHostCompatibilityReport inspect(Connection connection, DashboardRegisteredHostQualification target) throws SQLException {
        acquireFence(connection);
        var counts = new EnumMap<DashboardHostCompatibility.Kind, Long>(DashboardHostCompatibility.Kind.class);
        for (var kind : DashboardHostCompatibility.Kind.values()) counts.put(kind, 0L);
        var blockers = new ArrayList<DashboardHostCompatibilityReport.Blocker>();
        int records = 0; long bytes = 0; long incompatible = 0;
        try (var statement = connection.prepareStatement(INVENTORY)) {
            statement.setQueryTimeout(10); statement.setFetchSize(128); statement.setMaxRows(maxRecords + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String requirements = rows.getString("requirements");
                    int length = requirements.getBytes(StandardCharsets.UTF_8).length;
                    bytes += length;
                    if (++records > maxRecords || length > 1024 * 1024 || bytes > maxBytes) {
                        throw new IllegalStateException("HOST_PREFLIGHT_BUDGET_EXCEEDED");
                    }
                    var kind = DashboardHostCompatibility.Kind.valueOf(rows.getString("kind"));
                    counts.merge(kind, 1L, Long::sum);
                    var reason = DashboardHostCompatibility.inspect(target.descriptor(), kind, JSON.readTree(requirements));
                    if (reason.isPresent()) {
                        incompatible++;
                        if (blockers.size() < maxExamples) blockers.add(new DashboardHostCompatibilityReport.Blocker(
                                kind, rows.getObject("id", UUID.class), reason.orElseThrow()));
                    }
                }
            }
        }
        return new DashboardHostCompatibilityReport(target, counts, incompatible, blockers);
    }

    /** 验证显式operator事务并取得共享发布围栏的排他侧，调用方持有至事务结束。
     * @param connection READ COMMITTED的operator连接
     * @throws SQLException 权限/连接/锁失败
     */
    public static void acquireFence(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalArgumentException("HOST_PREFLIGHT_TRANSACTION_REQUIRED");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var role = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                if (!role.next() || !role.getBoolean(1)) throw new IllegalArgumentException("HOST_PREFLIGHT_OPERATOR_REQUIRED");
            }
            statement.execute("SET LOCAL statement_timeout='10s'");
            statement.execute("SET LOCAL lock_timeout='10s'");
            statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('dashboard-host-deployment-v1',148))");
        }
    }
}
