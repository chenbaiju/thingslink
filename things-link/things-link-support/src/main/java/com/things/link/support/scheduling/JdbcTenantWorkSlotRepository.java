package com.things.link.support.scheduling;

import com.things.link.shared.id.Uuid7;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** 通过 SECURITY DEFINER 函数维护集群级租户工作槽。 */
@Repository
public class JdbcTenantWorkSlotRepository implements TenantWorkSlotRepository {

    /** 支持模块 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已使用最小权限应用角色的数据源 */
    public JdbcTenantWorkSlotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Lease> tryAcquire(WorkType workType, UUID tenantId, Duration duration) {
        UUID token = Uuid7.generate();
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT try_claim_tenant_work_slot(?,?,?,?)",
                Boolean.class,
                workType.name(),
                tenantId,
                token,
                Math.toIntExact(duration.toSeconds()));
        return Boolean.TRUE.equals(acquired)
                ? Optional.of(new Lease(workType, tenantId, token))
                : Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public boolean release(Lease lease) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT release_tenant_work_slot(?,?,?)",
                Boolean.class,
                lease.workType().name(),
                lease.tenantId(),
                lease.token()));
    }
    /** {@inheritDoc} */
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean fence(Lease lease) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT fence_tenant_work_slot(?,?,?)", Boolean.class,
                lease.workType().name(), lease.tenantId(), lease.token()));
    }

}
