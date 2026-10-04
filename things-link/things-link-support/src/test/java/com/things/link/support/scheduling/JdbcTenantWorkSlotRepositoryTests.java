package com.things.link.support.scheduling;

import com.things.link.testing.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 验证两个应用实例竞争同租户通知槽时只有一个有效持有者。 */
class JdbcTenantWorkSlotRepositoryTests extends AbstractIntegrationTest {

    /** 用同一数据源构造两个仓储对象，等价两个健康实例调用数据库协议。 */
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 同租户互斥、不同租户并行，且旧 token 不能释放新持有者。 */
    @Test
    void enforcesOneClusterLeasePerTenantWithoutSerializingOtherTenants() {
        JdbcTenantWorkSlotRepository instanceA = new JdbcTenantWorkSlotRepository(jdbcTemplate);
        JdbcTenantWorkSlotRepository instanceB = new JdbcTenantWorkSlotRepository(jdbcTemplate);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantWorkSlotRepository.Lease leaseA = instanceA
                .tryAcquire(TenantWorkSlotRepository.WorkType.NOTIFICATION, tenantA, Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(instanceB.tryAcquire(
                TenantWorkSlotRepository.WorkType.NOTIFICATION, tenantA, Duration.ofSeconds(30)))
                .as("另一个应用实例不能同时持有同租户通知槽")
                .isEmpty();
        assertThat(instanceB.tryAcquire(
                TenantWorkSlotRepository.WorkType.NOTIFICATION, tenantB, Duration.ofSeconds(30)))
                .as("健康租户不能被其他租户的慢通知串行化")
                .isPresent();
        assertThat(instanceB.release(new TenantWorkSlotRepository.Lease(
                TenantWorkSlotRepository.WorkType.NOTIFICATION, tenantA, UUID.randomUUID())))
                .isFalse();
        assertThat(instanceA.release(leaseA)).isTrue();
        assertThat(instanceB.tryAcquire(
                TenantWorkSlotRepository.WorkType.NOTIFICATION, tenantA, Duration.ofSeconds(30)))
                .isPresent();
    }
}
