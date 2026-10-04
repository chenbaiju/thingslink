package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualification;
import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualificationPort;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADR0148平台操作事务；没有Spring常驻operator数据源，提交/回滚归CLI所有。 */
public final class JdbcHostActivation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DashboardRegisteredHostQualificationPort hosts;

    /** @param hosts 每次执行真实文件资格核验的显式目标端口 */
    public JdbcHostActivation(DashboardRegisteredHostQualificationPort hosts) { this.hosts = hosts; }

    /** 在排他锁内重新验目标及全库，仅返回只读报告，不写选择。
     * @param connection operator事务 @param version 精确目标 @return JSON安全报告
     * @throws SQLException 数据库失败
     */
    public Map<String, Object> preflight(Connection connection, String version) throws SQLException {
        JdbcHostCompatibilityInventory.acquireFence(connection);
        var target = qualify(version);
        var report = new JdbcHostCompatibilityInventory().inspect(connection, target);
        return Map.of("result", "PREFLIGHT", "activationAuthorized", false, "compatible", report.compatible(),
                "examplesTruncated", report.examplesTruncated(), "report", report);
    }

    /** 完整复验并原子暂存选择/历史；调用方尚须commit才可输出COMMITTED。
     * @param connection operator事务 @param version 精确版本 @param expected CAS前置 @param operation 幂等操作ID
     * @return 已提交后才可对外确认的回执 @throws SQLException 数据库失败
     */
    public Map<String, Object> activate(Connection connection, String version, long expected, UUID operation) throws SQLException {
        if (expected < 0 || expected == Long.MAX_VALUE) throw new IllegalArgumentException("INPUT_INVALID");
        JdbcHostCompatibilityInventory.acquireFence(connection);
        DashboardRegisteredHostQualification target = qualify(version);
        var previous = current(connection);
        var prior = history(connection, operation);
        if (!prior.isEmpty()) {
            if (!prior.get("expectedRevision").equals(expected) || !prior.get("hostVersion").equals(version)
                    || !prior.get("artifactDigest").equals(target.artifactDigest())
                    || !prior.get("sourceDigest").equals(target.sourceDigest())) throw new Rejected("OPERATION_CONFLICT");
            return receipt(prior, previous, true);
        }
        long actual = previous.isEmpty() ? 0 : (long) previous.get("revision");
        if (expected != actual) throw new Rejected("REVISION_CONFLICT");
        var report = new JdbcHostCompatibilityInventory().inspect(connection, target);
        if (!report.compatible()) throw new Rejected("INCOMPATIBLE");
        String sql = """
                INSERT INTO public.dash_host_deployment(slot,revision,host_version,artifact_digest,source_digest,operation_id,activated_at)
                VALUES (1,?,?,?,?,?,clock_timestamp())
                ON CONFLICT (slot) DO UPDATE SET revision=EXCLUDED.revision,host_version=EXCLUDED.host_version,
                  artifact_digest=EXCLUDED.artifact_digest,source_digest=EXCLUDED.source_digest,
                  operation_id=EXCLUDED.operation_id,activated_at=EXCLUDED.activated_at
                WHERE dash_host_deployment.revision=?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, expected + 1); statement.setString(2, version);
            statement.setString(3, target.artifactDigest()); statement.setString(4, target.sourceDigest());
            statement.setObject(5, operation); statement.setLong(6, expected);
            if (statement.executeUpdate() != 1) throw new Rejected("REVISION_CONFLICT");
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.dash_host_deployment_history
                  (revision,operation_id,expected_revision,previous_selection,host_version,artifact_digest,source_digest,database_actor,activated_at)
                SELECT revision,operation_id,?,?::jsonb,host_version,artifact_digest,source_digest,session_user,activated_at
                FROM public.dash_host_deployment WHERE slot=1
                """)) {
            statement.setLong(1, expected);
            statement.setString(2, previous.isEmpty() ? null : JSON.writeValueAsString(previous));
            if (statement.executeUpdate() != 1) throw new Rejected("HISTORY_WRITE_FAILED");
        }
        return receipt(history(connection, operation), current(connection), false);
    }

    /** 持相同围栏核对操作与当前选择；未找到不等于曾经的网络操作已回滚。
     * @param connection operator事务 @param operation 待核对操作ID @return 无凭据的状态
     * @throws SQLException 数据库失败
     */
    public Map<String, Object> status(Connection connection, UUID operation) throws SQLException {
        JdbcHostCompatibilityInventory.acquireFence(connection);
        var history = history(connection, operation);
        return Map.of("result", "STATUS", "found", !history.isEmpty(), "operationId", operation,
                "operation", history, "current", current(connection));
    }

    private DashboardRegisteredHostQualification qualify(String version) {
        return hosts.findRegistered(version).orElseThrow(() -> new Rejected("HOST_UNAVAILABLE"));
    }
    private static Map<String, Object> receipt(Map<String, Object> history, Map<String, Object> current, boolean replay) {
        return Map.of("result", "COMMITTED", "replayed", replay, "operation", history, "current", current);
    }
    private static Map<String, Object> current(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision,host_version,artifact_digest,source_digest,operation_id FROM public.dash_host_deployment WHERE slot=1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) return Map.of();
            return Map.of("revision", rows.getLong(1), "hostVersion", rows.getString(2), "artifactDigest", rows.getString(3),
                    "sourceDigest", rows.getString(4), "operationId", rows.getObject(5, UUID.class));
        }
    }
    private static Map<String, Object> history(Connection connection, UUID operation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision,expected_revision,host_version,artifact_digest,source_digest,database_actor,activated_at,previous_selection::text
                FROM public.dash_host_deployment_history WHERE operation_id=?
                """)) {
            statement.setObject(1, operation);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Map.of();
                var result = new LinkedHashMap<String, Object>();
                result.put("operationId", operation); result.put("revision", rows.getLong(1)); result.put("expectedRevision", rows.getLong(2));
                result.put("hostVersion", rows.getString(3)); result.put("artifactDigest", rows.getString(4)); result.put("sourceDigest", rows.getString(5));
                result.put("databaseActor", rows.getString(6)); result.put("activatedAt", rows.getTimestamp(7).toInstant().toString());
                result.put("previousSelection", rows.getString(8) == null ? null : JSON.readTree(rows.getString(8)));
                return result;
            }
        }
    }
    /** 安全固定拒绝分类，绝不复制数据库异常或输入正文。 */
    public static final class Rejected extends RuntimeException {
        /** @param code 固定内部类别 */
        Rejected(String code) { super(code); }
    }
}
