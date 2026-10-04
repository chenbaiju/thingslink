package com.things.link.bootstrap.integration;

import com.things.link.device.domain.DeviceMqttConnectionRepository;
import com.things.link.device.domain.DeviceMqttConnectionRepository.Scope;
import com.things.link.device.domain.DeviceMqttConnectionRepository.Ticket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0200真实PG内核；不把仓储受理冒充Broker/来源生产接线。 */
class MqttConnectionLedgerTests extends WebhookFixture {
    /** 真实持久仓储及外部事务代理。 */
    @Autowired DeviceMqttConnectionRepository ledger;
    /** 每例独立的有效Client ID范围。 */
    Scope scope;

    /** 存量无绑定MQTT配置0。 */
    @BeforeEach void scope() {
        scope = new Scope(tenant, project, device, 1, 0, "tc-device-" + "a".repeat(64));
    }
    /** 仅清理本例拥有的事实，历史来源由父夹具处理。 */
    @AfterEach void cleanLedger() {
        for (String table : new String[]{"dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor", "dev_access_binding"})
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    /** 所有写入必须在调用方事务内，防止连接事实先于来源单独提交。 */
    @Test void requiresCallerTransaction() {
        assertThatThrownBy(() -> ledger.issue(scope)).isInstanceOf(IllegalTransactionStateException.class);
    }
    /** 认证只发票据，不提前制造在线连接；次序来自数据库。 */
    @Test void issueIsUniqueMonotonicAndDoesNotCreateConnection() {
        Ticket first = issue(), second = issue();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.order()).isGreaterThan(first.order());
        assertThat(rows("dev_connection")).isZero();
        assertThat(rows("sys_outbox_event")).isZero();
        assertThat(inTx(() -> ledger.permits(scope, first.id()))).isTrue();
    }
    /** 重复connected只承认重放，不重复连接历史。 */
    @Test void duplicateConnectedDoesNotInsertAgain() {
        Ticket one = issue();
        assertThat(connect(one).changed()).isTrue();
        var repeated = connect(one);
        assertThat(repeated.accepted()).isTrue(); assertThat(repeated.changed()).isFalse();
        assertThat(rows("dev_connection")).isEqualTo(1);
    }
    /** 先新后旧的connected不能把旧认证次序恢复为活跃连接。 */
    @Test void lateConnectedCannotReplaceNewerConnection() {
        Ticket old = issue(), current = issue();
        assertThat(connect(current).accepted()).isTrue();
        assertThat(connect(old).accepted()).isFalse();
        assertThat(active(current.id())).isTrue();
        assertThat(inTx(() -> ledger.permits(scope, old.id()))).isFalse();
    }
    /** 旧断连只对应旧UUID，不能关闭新连接；原连接只关闭一次。 */
    @Test void lateDisconnectCannotCloseReplacement() {
        Ticket old = issue(); connect(old);
        Ticket current = issue();
        assertThat(connect(current).closedConnections()).isEqualTo(1);
        var late = inTx(() -> ledger.disconnected(scope, old.id(), "late"));
        assertThat(late.changed()).isFalse(); assertThat(active(current.id())).isTrue();
        assertThat(inTx(() -> ledger.permits(scope, old.id()))).isFalse();
    }
    /** 断连先到必须产生墓碑和下界，后到connected不复活。 */
    @Test void disconnectedBeforeConnectedCannotResurrect() {
        Ticket one = issue();
        assertThat(inTx(() -> ledger.disconnected(scope, one.id(), "normal")).changed()).isTrue();
        assertThat(connect(one).accepted()).isFalse();
        assertThat(rows("dev_connection")).isZero();
        assertThat(rows("dev_mqtt_session_cursor")).isEqualTo(1);
    }
    /** 新连接已完成又断开时，迟到原连接也必须失效。 */
    @Test void newerDisconnectBeforeConnectedClosesPriorConnection() {
        Ticket old = issue(); connect(old); Ticket current = issue();
        assertThat(inTx(() -> ledger.disconnected(scope, current.id(), "normal")).closedConnections()).isEqualTo(1);
        assertThat(active(old.id())).isFalse(); assertThat(connect(current).accepted()).isFalse();
        assertThat(inTx(() -> ledger.permits(scope, old.id()))).isFalse();
    }
    /** 其他Client ID有独立下界，不能被当前会话接管连带关闭。 */
    @Test void differentClientIdRemainsActive() {
        Ticket first = issue(); connect(first);
        Scope other = new Scope(tenant, project, device, 1, 0, "tc-device-" + "b".repeat(64));
        Ticket second = inTx(() -> ledger.issue(other).orElseThrow());
        inTx(() -> ledger.connected(other, second.id(), null, "other"));
        inTx(() -> ledger.disconnected(scope, first.id(), "normal"));
        assertThat(active(second.id())).isTrue();
        assertThat(inTx(() -> ledger.permits(other, second.id()))).isTrue();
    }
    /** 同UUID但scope/Client ID不同仍拒绝，不能凭UUID切换租户。 */
    @Test void wrongScopeAndUnknownIdAreRejected() {
        Ticket one = issue();
        Scope wrong = new Scope(tenant, project, device, 1, 0, "tc-device-" + "b".repeat(64));
        assertThat(inTx(() -> ledger.connected(wrong, one.id(), null, null)).accepted()).isFalse();
        assertThat(inTx(() -> ledger.connected(scope, UUID.randomUUID(), null, null)).accepted()).isFalse();
        Scope wrongTenant = new Scope(UUID.randomUUID(), project, device, 1, 0, scope.sessionId());
        assertThat(inTx(() -> ledger.issue(wrongTenant))).isEmpty();
        assertThat(rows("dev_connection")).isZero();
    }
    /** 控制已经关闭原行后即使账本仍ACTIVE也不可获ACL许可。 */
    @Test void closedAuthoritativeConnectionCannotRetainPermission() {
        Ticket one = issue(); connect(one);
        owner.update("UPDATE dev_connection SET disconnected_at=clock_timestamp() WHERE mqtt_connection_id=?", one.id());
        assertThat(inTx(() -> ledger.permits(scope, one.id()))).isFalse();
        assertThat(connect(one).accepted()).isFalse();
    }
    /** 原凭据版本失效不签发、不接纳新连接。 */
    @Test void oldCredentialCannotIssueOrEstablish() {
        Ticket one = issue();
        owner.update("UPDATE dev_device SET credential_version=2 WHERE id=?", device);
        assertThat(inTx(() -> ledger.issue(scope))).isEmpty();
        assertThat(connect(one).accepted()).isFalse();
        assertThat(inTx(() -> ledger.permits(scope, one.id()))).isFalse();
    }
    /** 归档仅保留原连接关闭，不允许新认证或活动。 */
    @Test void archivedProjectOnlyAllowsHistoricalDisconnect() {
        Ticket one = issue(); connect(one);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        assertThat(inTx(() -> ledger.issue(scope))).isEmpty();
        assertThat(inTx(() -> ledger.permits(scope, one.id()))).isFalse();
        assertThat(inTx(() -> ledger.disconnected(scope, one.id(), "normal")).closedConnections()).isEqualTo(1);
    }
    /** 配置切换或禁用均不能把无行MQTT解释为许可。 */
    @Test void nativeBindingRejectsOldTicket() {
        Ticket one = issue();
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')", device, tenant, project);
        assertThat(inTx(() -> ledger.issue(scope))).isEmpty();
        assertThat(connect(one).accepted()).isFalse();
    }
    /** 过期PENDING不能建会话，清理后UUID仍不能从回调重建。 */
    @Test void expiredPendingIsDeniedAndCannotResurrectAfterDeletion() {
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id,created_at,expires_at)
                VALUES(?,?,?,?,1,0,?,clock_timestamp()-interval '301 seconds',clock_timestamp()-interval '1 second')
                """, id, tenant, project, device, scope.sessionId());
        assertThat(inTx(() -> ledger.connected(scope, id, null, null)).accepted()).isFalse();
        assertThat(inTx(() -> ledger.permits(scope, id))).isFalse();
        issue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_mqtt_connection_ticket WHERE id=?", Integer.class, id)).isZero();
        assertThat(inTx(() -> ledger.connected(scope, id, null, null)).accepted()).isFalse();
    }
    /** 原调用方提交失败时，连接、票据状态、下界全部回滚。 */
    @Test void outerFailureRollsBackEntireAdmission() {
        Ticket one = issue();
        assertThatThrownBy(() -> tx.execute(s -> {
            ledger.connected(scope, one.id(), null, null);
            throw new IllegalStateException("source-failure-fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows("dev_connection")).isZero(); assertThat(rows("dev_mqtt_session_cursor")).isZero();
        assertThat(owner.queryForObject("SELECT state FROM dev_mqtt_connection_ticket WHERE id=?", String.class, one.id())).isEqualTo("PENDING");
        assertThat(connect(one).accepted()).isTrue();
    }
    /** 数据库身份、终态及游标守卫拒绝篡改，不能只靠Java调用者自律。 */
    @Test void databaseRejectsIdentityMutationAndCursorRegression() {
        Ticket one = issue(); connect(one);
        assertThatThrownBy(() -> owner.update("UPDATE dev_mqtt_connection_ticket SET credential_version=2 WHERE id=?", one.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("UPDATE dev_mqtt_session_cursor SET max_observed_order=0 WHERE device_id=?", device)).isInstanceOf(DataAccessException.class);
        inTx(() -> ledger.disconnected(scope, one.id(), "normal"));
        assertThatThrownBy(() -> owner.update("UPDATE dev_mqtt_connection_ticket SET state='PENDING' WHERE id=?", one.id())).isInstanceOf(DataAccessException.class);
    }
    /** 并发重放由实际PG设备锁仲裁，只有一次首次受理。 */
    @Test void concurrentConnectedCreatesOneHistory() throws Exception {
        Ticket one = issue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 16; i++) tasks.add(() -> connect(one).changed());
            int changes = 0;
            for (var future : executor.invokeAll(tasks, 15, TimeUnit.SECONDS)) if (future.get()) changes++;
            assertThat(changes).isEqualTo(1);
        }
        assertThat(rows("dev_connection")).isEqualTo(1);
    }
    /** 实际容量计数在签发事务内，达到上限不得返回无票据许可。 */
    @Test void pendingCapacityDeniesWithoutMutatingActiveConnection() {
        Ticket active = issue(); connect(active);
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id)
                SELECT gen_random_uuid(),?,?,?,1,0,? FROM generate_series(1,1024)
                """, tenant, project, device, scope.sessionId());
        assertThat(inTx(() -> ledger.issue(scope))).isEmpty();
        assertThat(active(active.id())).isTrue();
        assertThat(inTx(() -> ledger.permits(scope, active.id()))).isTrue();
    }
    /** 新增连接历史不能冒用其他Client ID，也不能在建立后替换原UUID。 */
    @Test void connectionHistoryIsBoundToOriginalTicket() {
        Ticket one = issue(); connect(one);
        assertThatThrownBy(() -> owner.update("UPDATE dev_connection SET mqtt_connection_id=? WHERE mqtt_connection_id=?", UUID.randomUUID(), one.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("UPDATE dev_connection SET session_id='other' WHERE mqtt_connection_id=?", one.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,mqtt_connection_id)
                VALUES(gen_random_uuid(),?,?,?,'wrong','MQTT',0,?)
                """, tenant, project, device, one.id())).isInstanceOf(DataAccessException.class);
    }
    /** 过期字段只限制PENDING，持续活跃连接不被五分钟期限误踢。 */
    @Test void activeConnectionSurvivesPendingDeadline() {
        UUID id = oldActive();
        assertThat(inTx(() -> ledger.permits(scope, id))).isTrue();
        assertThat(inTx(() -> ledger.disconnected(scope, id, "normal")).closedConnections()).isEqualTo(1);
        assertThat(inTx(() -> ledger.permits(scope, id))).isFalse();
    }
    /** 已关闭高次序票据可清理，但其下界不能丢失而放行仍有效的旧PENDING。 */
    @Test void expiryCleanupKeepsWatermarkForOlderPending() {
        Ticket old = issue(); UUID newer = oldActive();
        inTx(() -> ledger.disconnected(scope, newer, "normal"));
        issue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_mqtt_connection_ticket WHERE id=?", Integer.class, newer)).isZero();
        assertThat(connect(old).accepted()).isFalse();
        assertThat(rows("dev_mqtt_session_cursor")).isEqualTo(1);
    }
    /** RLS无范围看不到票据；插入伪造设备归属触发数据库拒绝。 */
    @Test void rowLevelSecurityAndScopeGuardRemainEffective() {
        issue();
        assertThat(inTx(() -> jdbc.queryForObject("SELECT count(*) FROM dev_mqtt_connection_ticket", Integer.class))).isZero();
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id)
                VALUES(gen_random_uuid(),?,?,?,1,0,?)
                """, UUID.randomUUID(), project, device, scope.sessionId())).isInstanceOf(DataAccessException.class);
    }
    /** 测试专用历史ACTIVE事实：原签发已过期，但当前连接仍真实存在于账本。 */
    private UUID oldActive() {
        UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id,state,created_at,expires_at)
                VALUES(?,?,?,?,1,0,?,'ACTIVE',clock_timestamp()-interval '301 seconds',clock_timestamp()-interval '1 second')
                """, id, tenant, project, device, scope.sessionId());
        owner.update("""
                INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,mqtt_connection_id)
                VALUES(gen_random_uuid(),?,?,?,?,'MQTT',0,?)
                """, tenant, project, device, scope.sessionId(), id);
        return id;
    }
    /** 公共辅助仍走真实事务代理，不使用owner执行生产逻辑。 */
    private <T> T inTx(Supplier<T> operation) { return tx.execute(s -> operation.get()); }
    /** 签发一张实际原始票据。 */
    private Ticket issue() { return inTx(() -> ledger.issue(scope).orElseThrow()); }
    /** 模拟可信Broker受理，仅仓储层不追加生产来源。 */
    private DeviceMqttConnectionRepository.Transition connect(Ticket ticket) {
        return inTx(() -> ledger.connected(scope, ticket.id(), "127.0.0.1", "node"));
    }
    /** 从实际连接事实检查原UUID仍活跃。 */
    private boolean active(UUID ticket) {
        return Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM dev_connection WHERE mqtt_connection_id=? AND disconnected_at IS NULL)", Boolean.class, ticket));
    }
}
