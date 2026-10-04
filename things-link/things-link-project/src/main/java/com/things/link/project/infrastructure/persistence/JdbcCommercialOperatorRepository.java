package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.CommercialOperatorRepository;
import com.things.link.project.domain.plan.ResourcePackageDimension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 单语句审批摘要，不用旧缓存拼接当前版本；SQL标识只来自固定枚举。 */
@Repository
public class JdbcCommercialOperatorRepository implements CommercialOperatorRepository {
    private final JdbcTemplate jdbc;
    private static final ResourcePackageDimension[] DIMENSIONS=Arrays.stream(ResourcePackageDimension.values())
            .filter(d -> d.fixedUnit()!=null).toArray(ResourcePackageDimension[]::new);
    private static final String PROJECTION=Arrays.stream(DIMENSIONS).map(d ->
            "LEAST(q."+column(d)+"::numeric + tenant_resource_package_addon(t.id,'"+d.name()+"','"+
            d.fixedUnit()+"','"+d.window()+"',statement_timestamp()),9223372036854775807)::text AS \""+d.name()+"\"")
            .collect(Collectors.joining(","));
    /** @param jdbc 当前事务的JDBC */
    public JdbcCommercialOperatorRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** {@inheritDoc} */
    @Override public boolean enabled(UUID actor,boolean lock) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT commercial_operator_enabled(?,?)",Boolean.class,actor,lock));
    }
    /** {@inheritDoc} */
    @Override public Optional<Snapshot> snapshot(UUID tenant) {
        return jdbc.query("SELECT t.name,t.quota_policy_assignment_version,"+PROJECTION+
                " FROM sys_tenant t JOIN sys_quota_policy q ON q.id=t.quota_policy_id WHERE t.id=? AND t.status='ACTIVE' AND q.plan_template",
                (rs,row) -> {
                    Map<String,String> values=new LinkedHashMap<>();
                    for (var dimension:DIMENSIONS) values.put(dimension.name(),rs.getString(dimension.name()));
                    return new Snapshot(rs.getString("name"),rs.getLong("quota_policy_assignment_version"),Map.copyOf(values));
                },tenant).stream().findFirst();
    }
    private static String column(ResourcePackageDimension dimension) {
        return switch(dimension) {
            case PROJECTS_MAX -> "projects_max";
            case DEVICES_MAX -> "device_count_limit";
            case END_USERS_MAX -> "end_users_max";
            case DASHBOARDS_MAX -> "dashboards_max";
            case EXTERNAL_COLLABORATOR_SEATS -> "external_collaborator_seats";
            case UPLINK_MESSAGE_DAILY -> "uplink_message_daily_limit";
            case DOWNLINK_MESSAGE_DAILY -> "downlink_message_daily_limit";
            case REST_API_WRITE_DAILY -> "rest_api_call_daily_limit";
            case REST_API_RATE_PER_MINUTE -> "rest_api_rate_per_minute";
            case WEBSOCKET_CONNECTION_CONCURRENT -> "websocket_connection_limit";
            case SCRIPT_RULE_CONCURRENCY -> "rule_tenant_concurrency_limit";
            case SCRIPT_RULE_EXECUTION_DAILY -> "script_execution_daily_limit";
            case SCRIPT_RULE_CPU_MILLIS_DAILY -> "script_cpu_millis_daily_limit";
            case NOTIFICATION_DELIVERY_DAILY -> "notification_delivery_daily_limit";
            case STORAGE_LIMIT -> "storage_bytes_limit";
            case HISTORY_WINDOW -> throw new IllegalArgumentException("历史扩容未开放");
        };
    }
}
