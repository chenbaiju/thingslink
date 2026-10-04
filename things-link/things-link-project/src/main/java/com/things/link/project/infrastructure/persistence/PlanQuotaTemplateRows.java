package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.plan.EffectivePlanQuota;
import com.things.link.project.domain.plan.PlanQuotaTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

/**
 * S14-1b 冻结配额模板行的共享列清单与行映射（S14-4c 抽取）。
 *
 * <p>为什么单独抽出来：有效策略读取（{@code JdbcEffectiveQuotaPolicyRepository}）与租户套餐摘要
 * （{@code JdbcPlanSummaryRepository}）都要把 {@code sys_quota_policy} 的 17 个冻结列还原成
 * {@link PlanQuotaTemplate}。复制一份列清单意味着以后新增维度时两处只改一处——正是
 * 「同一事实只有一份读法」要避免的。列清单与映射因此放在这里，两处共用。
 *
 * <p>列清单固定带别名 {@code q}，调用方必须把 {@code sys_quota_policy} 命名为 {@code q}。
 *
 * <p>本类不判断「哪一行才是租户的有效模板」：那是各自查询的职责（按租户绑定、按修订版或按
 * {@code plan_template} 过滤）。它只负责把一行完整还原，避免半截模板被当成有效值。
 */
final class PlanQuotaTemplateRows {

    /**
     * 冻结模板的完整列清单；所有查询必须逐字使用，避免漏列。
     *
     * <p>列名带 {@code q.} 前缀：本清单总是与 {@code sys_quota_policy} 的别名 {@code q} 连用，
     * 而调用方的 FROM 子句还可能连接 {@code sys_tenant}／{@code sys_project}（两者都有 {@code id}），
     * 不加限定会直接以「column reference "id" is ambiguous」失败。
     */
    static final String COLUMNS = """
            q.id, q.version, q.projects_max, q.device_count_limit, q.end_users_max, q.dashboards_max,
            q.external_collaborator_seats, q.history_window_unit, q.history_window_amount,
            q.uplink_message_daily_limit, q.downlink_message_daily_limit, q.rest_api_call_daily_limit,
            q.rest_api_rate_per_minute, q.websocket_connection_limit, q.rule_tenant_concurrency_limit,
            q.script_execution_daily_limit, q.script_cpu_millis_daily_limit,
            q.notification_delivery_daily_limit, q.storage_bytes_limit
            """;

    /** 一行冻结模板：模板身份 + 单调版本 + 完整冻结值。 */
    static final RowMapper<PlanQuotaTemplateRow> MAPPER = (resultSet, rowNumber) ->
            new PlanQuotaTemplateRow(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getLong("version"),
                    new PlanQuotaTemplate(
                            resultSet.getLong("projects_max"),
                            resultSet.getLong("device_count_limit"),
                            resultSet.getLong("end_users_max"),
                            resultSet.getLong("dashboards_max"),
                            resultSet.getLong("external_collaborator_seats"),
                            resultSet.getString("history_window_unit"),
                            resultSet.getLong("history_window_amount"),
                            resultSet.getLong("uplink_message_daily_limit"),
                            resultSet.getLong("downlink_message_daily_limit"),
                            resultSet.getLong("rest_api_call_daily_limit"),
                            resultSet.getLong("rest_api_rate_per_minute"),
                            resultSet.getLong("websocket_connection_limit"),
                            resultSet.getLong("rule_tenant_concurrency_limit"),
                            resultSet.getLong("script_execution_daily_limit"),
                            resultSet.getLong("script_cpu_millis_daily_limit"),
                            resultSet.getLong("notification_delivery_daily_limit"),
                            resultSet.getLong("storage_bytes_limit")));

    /** 工具类不可实例化。 */
    private PlanQuotaTemplateRows() {
    }

    /**
     * 一行冻结模板事实（尚未合成任何资源包或人工调整）。
     *
     * @param policyId 配额模板 ID（{@code sys_quota_policy.id}）
     * @param version 模板单调版本
     * @param template 完整冻结配额模板
     */
    record PlanQuotaTemplateRow(UUID policyId, long version, PlanQuotaTemplate template) {

        /**
         * 转成「基础档」有效配额投影（不含资源包）。
         *
         * @return 未合成的有效配额投影，供 {@link EffectivePlanQuota#compose} 继续合成
         */
        EffectivePlanQuota toBaseQuota() {
            return new EffectivePlanQuota(policyId, version, template);
        }
    }
}
