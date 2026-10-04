package com.things.link.bootstrap.integration;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实应用角色/PG下的配置CAS与代次，尚不开放管理写入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "things-link.integration.api-key.enabled=true")
class DeviceAccessConfigurationRepositoryTests extends OpenDeviceHttpFixture {
    /** 真实事务型仓储。 */
    @Autowired private DeviceAccessSessionRepository bindings;
    /** 当前事务双轴范围。 */
    @Autowired private TransactionLocalRlsScope rls;

    /** 清理本用例配置，先于父夹具删除设备。 */
    @AfterEach
    void clearBindings() {
        owner.update("DELETE FROM dev_access_binding WHERE project_id=?", project);
    }

    /** 在真实应用事务与范围中运行，不能用所有者权限代替仓储测试。 */
    private DeviceAccessBinding change(long expected, TransportProtocol protocol, boolean enabled, boolean force) {
        return tx.execute(s -> {
            rls.establish(tenant, project);
            return bindings.changeBinding(tenant, project, device, expected, protocol, enabled, force);
        });
    }

    /** 存量MQTT同值不落行，真实配置切换再切回保持原行及增长代次。 */
    @Test
    void legacyNoopAndRoundTripPreserveEpoch() {
        assertThat(change(0, TransportProtocol.MQTT, true, false).configVersion()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(change(0, TransportProtocol.TCP, true, false).configVersion()).isEqualTo(1);
        assertThat(change(1, TransportProtocol.MQTT, true, false).configVersion()).isEqualTo(2);
        assertThat(change(2, TransportProtocol.MQTT, false, false).configVersion()).isEqualTo(3);
        assertThat(change(3, TransportProtocol.MQTT, true, false).configVersion()).isEqualTo(4);
        assertThat(change(4, TransportProtocol.MQTT, true, false).configVersion()).isEqualTo(4);
    }

    /** 凭据变化强制推进代次，不用伪造协议变化来失效旧许可。 */
    @Test
    void credentialAdvancePersistsExplicitMqtt() {
        assertThat(change(0, TransportProtocol.MQTT, true, true).configVersion()).isEqualTo(1);
        assertThat(change(1, TransportProtocol.MQTT, true, true).configVersion()).isEqualTo(2);
        assertThatThrownBy(() -> change(1, TransportProtocol.MQTT, true, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_CONFLICT));
    }

    /** 两个真实事务竞争同一个期望版本，只允许一方成功，另一方明确冲突。 */
    @Test
    void concurrentCompareAndSetHasOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> results = List.of(pool.submit(() -> compete(start, TransportProtocol.HTTP)),
                    pool.submit(() -> compete(start, TransportProtocol.COAP)));
            start.countDown();
            Object a = results.get(0).get(15, TimeUnit.SECONDS), b = results.get(1).get(15, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).filteredOn(DeviceAccessBinding.class::isInstance).hasSize(1);
            assertThat(List.of(a, b)).contains(DeviceErrorCode.ACCESS_CONFIG_CONFLICT);
            assertThat(owner.queryForObject("SELECT config_version FROM dev_access_binding WHERE device_id=?", Long.class, device)).isEqualTo(1);
        }
    }

    /** 有界启动屏障，异常保留业务码而不是把数据库故障当作预期冲突。 */
    private Object compete(CountDownLatch start, TransportProtocol protocol) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try { return change(0, protocol, true, false); }
        catch (BusinessException ex) { return ex.errorCode(); }
    }

    /** GUC项目范围不是完整租户证明，仓储仍必须显式检查设备tenant。 */
    @Test
    void wrongTenantCannotWriteEvenWithCorrectProject() {
        UUID wrong = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> {
            rls.establish(wrong, project);
            return bindings.changeBinding(wrong, project, device, 0, TransportProtocol.HTTP, true, false);
        })).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_NOT_FOUND));
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
    }

    /** 最大版本允许同值查询式重放，但任何变化均不能溢出或局部提交。 */
    @Test
    void exhaustedEpochRejectsChangeAndAllowsExactNoop() {
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',?)",
                device, tenant, project, Long.MAX_VALUE);
        assertThat(change(Long.MAX_VALUE, TransportProtocol.MQTT, true, false).configVersion()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> change(Long.MAX_VALUE, TransportProtocol.MQTT, false, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.ACCESS_CONFIG_EXHAUSTED));
        assertThat(owner.queryForObject("SELECT enabled FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isTrue();
    }

    /** 外层事务失败撤销首次配置，不残留版本或活动事实。 */
    @Test
    void outerFailureRollsBackPersistedEpoch() {
        assertThatThrownBy(() -> tx.execute(s -> {
            rls.establish(tenant, project);
            bindings.changeBinding(tenant, project, device, 0, TransportProtocol.HTTP, true, false);
            throw new IllegalStateException("planned rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_access_binding WHERE device_id=?", Integer.class, device)).isZero();
    }

    /** 无事务调用拒绝，不让MANDATORY端口退化为自动提交配置写者。 */
    @Test
    void refusesAutocommitCall() {
        assertThatThrownBy(() -> bindings.changeBinding(tenant, project, device, 0, TransportProtocol.HTTP, true, false))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
