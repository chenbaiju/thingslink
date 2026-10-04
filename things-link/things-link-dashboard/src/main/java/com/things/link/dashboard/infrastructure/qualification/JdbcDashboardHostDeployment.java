package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardHostDeploymentPort;
import com.things.link.dashboard.application.publication.DashboardHostDeploymentSelection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/** ADR0147控制面公共制品选择；不读取租户业务，不能用autocommit丢失事务级围栏。 */
@Repository
public class JdbcDashboardHostDeployment implements DashboardHostDeploymentPort {
    private final JdbcTemplate jdbc;

    /** @param jdbc 加入调用方同一控制面事务的数据源 */
    public JdbcDashboardHostDeployment(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@inheritDoc} */
    @Override public Optional<DashboardHostDeploymentSelection> admitCurrent() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("宿主准入必须加入调用方事务");
        }
        var rows = jdbc.query("SELECT * FROM public.dashboard_host_admit_current()", (rs, index) -> {
            long revision = rs.getLong("revision");
            return revision == 0 ? Optional.<DashboardHostDeploymentSelection>empty()
                    : Optional.of(new DashboardHostDeploymentSelection(revision, rs.getString("host_version"),
                            rs.getString("artifact_digest"), rs.getString("source_digest")));
        });
        if (rows.size() != 1) throw new IllegalStateException("宿主准入返回数量异常");
        return rows.getFirst();
    }
}
