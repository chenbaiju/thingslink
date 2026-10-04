package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.ingestion.application.ModbusResponseUplinkNormalizer;
import com.things.link.ingestion.infrastructure.ModbusResponseKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.DisplayName;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12-P0-4f / D-121：已知请求关联不是授权凭据，响应归属必须匹配被读取的轮询归属。
 * 测试以已确权信封作为服务前置，并未执行 MQTT 连接鉴权或接入适配器。
 */
@DisplayName("Modbus 响应归属边界")
class ModbusPollResponseOwnershipTests extends AbstractModbusPollIntegrationTest {

    /** 使用真实响应与调度服务，不能以仓储 mock 隐藏跨归属修改。 */
    @Autowired private ModbusPollService pollService;
    /** 保留真实payload解析，以证明自报归属无法改变raw身份但requestId仍需业务授权。 */
    @Autowired private ModbusResponseUplinkNormalizer responseNormalizer;
    /** ERROR不进入版本/Kafka发送，隔离本片归属风险而保留生产消费入口。 */
    @Autowired private ModbusResponseKafkaConsumer responseConsumer;

    /** 正确的Kafka分区键和忽略自报身份都不能代替对已知requestId的归属检查。 */
    @Test
    void trustedEnvelopeWithForeignRequestAndSpoofedPayloadCannotClearVictim() throws Exception {
        Fixture victim = onlineSchedule();
        Fixture sender = onlineSchedule();
        scanDue();
        PollState victimBefore = state(victim);
        PollState senderBefore = state(sender);
        List<String> victimOutbox = outboxRows(victim);
        List<String> senderOutbox = outboxRows(sender);
        String payload = """
                {"requestId":"%s","status":"ERROR","data":[],"errorCode":"SLAVE_ERROR",
                 "tenantId":"%s","projectId":"%s","gatewayId":"%s"}
                """.formatted(victimBefore.requestId(), victim.tenantId(), victim.projectId(), victim.gatewayId());
        RawUplinkMessage raw = new RawUplinkMessage(sender.tenantId(), sender.projectId(), sender.gatewayId(),
                "tc/v1/" + sender.projectKey() + "/poll_gateway/up/modbus/response",
                payload.getBytes(StandardCharsets.UTF_8), 1, false, "d121-sender", Instant.now(), "d121-envelope");
        ModbusResponse parsed = responseNormalizer.tryNormalize(raw).orElseThrow();
        assertThat(parsed.requestId()).isEqualTo(victimBefore.requestId());
        assertThat(parsed.tenantId()).isEqualTo(sender.tenantId());
        assertThat(parsed.projectId()).isEqualTo(sender.projectId());
        assertThat(parsed.gatewayId()).isEqualTo(sender.gatewayId());
        dataTransaction(() -> {
            responseConsumer.consume(new ConsumerRecord<>(ModbusResponseKafkaConsumer.MODBUS_RESPONSE_TOPIC,
                    0, 0L, sender.gatewayId().toString(), parsed));
            return null;
        });
        assertThat(state(victim)).isEqualTo(victimBefore);
        assertThat(state(sender)).isEqualTo(senderBefore);
        assertThat(outboxRows(victim)).isEqualTo(victimOutbox);
        assertThat(outboxRows(sender)).isEqualTo(senderOutbox);
    }

    /** 同项目另一个合法网关不能用已知 requestId 完成受害网关的请求。 */
    @Test
    void anotherGatewayInTheSameProjectCannotSubmitSuccess() throws Exception {
        assertSameProjectGatewayRejected(ModbusResponse.Status.SUCCESS);
    }

    /** ERROR 与 SUCCESS 必须执行相同归属检查，不能成为清空他人轮询的旁路。 */
    @Test
    void anotherGatewayInTheSameProjectCannotSubmitError() throws Exception {
        assertSameProjectGatewayRejected(ModbusResponse.Status.ERROR);
    }

    /** 另一租户项目的合法网关即使持有请求关联，也不能取得受害项目的属性值。 */
    @Test
    void gatewayFromAnotherTenantProjectCannotSubmitSuccess() throws Exception {
        assertOtherTenantProjectRejected(ModbusResponse.Status.SUCCESS);
    }

    /** 另一租户项目发送 ERROR 不能推进或清除受害项目正在等待的请求。 */
    @Test
    void gatewayFromAnotherTenantProjectCannotSubmitError() throws Exception {
        assertOtherTenantProjectRejected(ModbusResponse.Status.ERROR);
    }

    /** 合法归属仍可完成并解码，防止以丢弃所有响应伪造归属隔离。 */
    @Test
    void matchingOwnerCompletesAndReturnsItsOwnPoint() throws Exception {
        Fixture fixture = onlineSchedule();
        scanDue();
        PollState before = state(fixture);
        List<String> outboxBefore = outboxRows(fixture);
        Optional<ModbusPollService.ResolvedModbusValue> resolved = dataTransaction(() -> pollService.handleResponse(
                response(identity(fixture), before.requestId(), ModbusResponse.Status.SUCCESS)));

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().gatewayId()).isEqualTo(fixture.gatewayId());
        assertThat(resolved.orElseThrow().subDeviceId()).isEqualTo(fixture.subDeviceId());
        assertThat(resolved.orElseThrow().messageId()).isEqualTo(before.requestId());
        assertThat(resolved.orElseThrow().propertyKey()).isEqualTo("temperature_0");
        assertThat(resolved.orElseThrow().value()).isEqualTo(-2.25d);
        assertThat(state(fixture).status()).isEqualTo("IDLE");
        assertThat(state(fixture).requestId()).isNull();
        assertThat(outboxRows(fixture)).isEqualTo(outboxBefore);
    }

    /** 身份合法但关联未知仍应幂等返回空，不能改变当前在途行或发送意图。 */
    @Test
    void unknownRequestDoesNotChangeTheMatchingOwnersPoll() throws Exception {
        Fixture fixture = onlineSchedule();
        scanDue();
        PollState before = state(fixture);
        List<String> outboxBefore = outboxRows(fixture);
        assertThat(dataTransaction(() -> pollService.handleResponse(
                response(identity(fixture), Uuid7.generate(), ModbusResponse.Status.SUCCESS)))).isEmpty();
        assertThat(state(fixture)).isEqualTo(before);
        assertThat(outboxRows(fixture)).isEqualTo(outboxBefore);
    }

    /** 额外网关使用受害项目现有合法类型，仅网关 ID 不同，单独检验网关归属。 */
    private void assertSameProjectGatewayRejected(ModbusResponse.Status status) throws Exception {
        Fixture victim = onlineSchedule();
        UUID anotherGatewayId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    SELECT ?, tenant_id, project_id, device_type_id, ?, '同项目另一合法网关', 'ONLINE'
                      FROM dev_device WHERE id = ?
                    """, anotherGatewayId, "other_" + anotherGatewayId.toString().replace("-", ""), victim.gatewayId())).isEqualTo(1);
        }
        scanDue();
        SenderIdentity other = new SenderIdentity(victim.tenantId(), victim.projectId(), anotherGatewayId);
        assertThat(other.gatewayId()).isNotEqualTo(victim.gatewayId());
        assertRejected(victim, other, status);
    }

    /** 两个项目都走真实配置物化及匿名扫描，不能用非法的伪造 poll 证明隔离。 */
    private void assertOtherTenantProjectRejected(ModbusResponse.Status status) throws Exception {
        Fixture victim = onlineSchedule();
        Fixture other = onlineSchedule();
        assertThat(other.tenantId()).isNotEqualTo(victim.tenantId());
        assertThat(other.projectId()).isNotEqualTo(victim.projectId());
        scanDue();
        PollState otherBefore = state(other);
        assertThat(otherBefore.status()).isEqualTo("IN_FLIGHT");
        List<String> otherOutboxBefore = outboxRows(other);
        assertRejected(victim, identity(other), status);
        assertThat(state(other)).isEqualTo(otherBefore);
        assertThat(outboxRows(other)).isEqualTo(otherOutboxBefore);
    }

    /** 独立 owner 快照在调用前后比较完整行，检测按已知 requestId 越权完成或释放。 */
    private void assertRejected(Fixture victim, SenderIdentity sender, ModbusResponse.Status status) throws Exception {
        PollState before = state(victim);
        assertThat(before.status()).isEqualTo("IN_FLIGHT");
        assertThat(before.requestId()).isNotNull();
        List<String> outboxBefore = outboxRows(victim);
        Optional<ModbusPollService.ResolvedModbusValue> resolved = dataTransaction(() -> pollService.handleResponse(
                response(sender, before.requestId(), status)));
        assertThat(state(victim)).isEqualTo(before);
        assertThat(resolved).isEmpty();
        assertThat(outboxRows(victim)).isEqualTo(outboxBefore);
    }

    /** 在线资格和点位均来自合法配置夹具，保留已有拓扑及已发布映射约束。 */
    private Fixture onlineSchedule() throws Exception {
        Fixture fixture = fixture(points(1));
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
        }
        pushConfig(fixture);
        return fixture;
    }

    /** 扫描可能同时处理多个本例在线夹具，仅逐项目核验在途事实，不假定固定返回数量。 */
    private void scanDue() {
        assertThat(dataTransaction(() -> pollService.scanDue(100))).isPositive();
    }

    /** 信封身份从合法设备事实取得；本测试不声称再次执行 MQTT 确权。 */
    private SenderIdentity identity(Fixture fixture) {
        return new SenderIdentity(fixture.tenantId(), fixture.projectId(), fixture.gatewayId());
    }

    /** SUCCESS 的完整小端 FLOAT32 输入独立解码为 -2.25，避免格式错误掩盖归属缺陷。 */
    private ModbusResponse response(SenderIdentity sender, UUID requestId, ModbusResponse.Status status) {
        return new ModbusResponse(requestId, sender.tenantId(), sender.projectId(), sender.gatewayId(), status,
                status == ModbusResponse.Status.SUCCESS ? List.of(0, 0x3f80) : List.of(),
                status == ModbusResponse.Status.ERROR ? "SLAVE_ERROR" : null, Instant.now(), "d121-ownership");
    }

    /** 无 HTTP 线程身份的 APP DATA 事务，归属必须由业务信封校验而不是测试预置 RLS 来保障。 */
    private <T> T dataTransaction(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                T result = action.get();
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                return result;
            });
        }
    }

    /** 轮询未启用项目 RLS 时 owner 查询也只限定本例项目，不改变或清理其他测试数据。 */
    private PollState state(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT request_id, status, row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ?")) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                PollState result = new PollState(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 比较所有发送意图完整快照，不以数量相同替代内容及归属没有变化。 */
    private List<String> outboxRows(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT row_to_json(o)::text FROM sys_outbox_event o WHERE project_id = ? ORDER BY id")) {
            parameters(statement, fixture.projectId());
            List<String> snapshots = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) snapshots.add(rows.getString(1));
            }
            return List.copyOf(snapshots);
        }
    }

    /** @param tenantId 已确权租户 @param projectId 已确权项目 @param gatewayId 已确权网关 */
    private record SenderIdentity(UUID tenantId, UUID projectId, UUID gatewayId) { }

    /** @param requestId 当前请求 @param status 当前阶段 @param completeRow 完整行快照 */
    private record PollState(UUID requestId, String status, String completeRow) { }
}
