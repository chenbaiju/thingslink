package com.things.link.device.infrastructure.emqx;

import com.things.link.device.domain.DeviceConnectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** D-038 EMQX 旧会话主动断开适配器测试。 */
class EmqxHttpDeviceSessionTerminatorTests {

    /** 凭据轮换后必须对每个活跃 clientId 发 DELETE，并使用独立管理凭据。 */
    @Test
    void disconnectsEveryActiveMqttSession() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        when(repository.findActiveMqttSessionIds(projectId, deviceId)).thenReturn(List.of("client-a", "client-b"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                "session-key:session-secret".getBytes(StandardCharsets.UTF_8));
        server.expect(once(), requestTo("http://emqx:18083/api/v5/clients/client-a"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(header(HttpHeaders.AUTHORIZATION, authorization))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(once(), requestTo("http://emqx:18083/api/v5/clients/client-b"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(header(HttpHeaders.AUTHORIZATION, authorization))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmqxHttpDeviceSessionTerminator terminator = terminator(repository, builder, registry,
                "session-key", "session-secret");

        terminator.disconnectAfterCommit(projectId, deviceId);

        assertThat(registry.get(DeviceSessionTerminationMetrics.ATTEMPTS)
                .tag("result", "disconnected").counter().count()).isEqualTo(2.0);
        server.verify();
    }

    /** Broker 回调可能先关闭会话；404 是幂等成功，不能误报为撤销失败。 */
    @Test
    void treatsMissingClientAsAlreadyDisconnected() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        when(repository.findActiveMqttSessionIds(projectId, deviceId)).thenReturn(List.of("gone-client"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://emqx:18083/api/v5/clients/gone-client"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmqxHttpDeviceSessionTerminator terminator = terminator(repository, builder, registry,
                "session-key", "session-secret");

        terminator.disconnectAfterCommit(projectId, deviceId);

        assertThat(registry.get(DeviceSessionTerminationMetrics.ATTEMPTS)
                .tag("result", "already_absent").counter().count()).isEqualTo(1.0);
        server.verify();
    }

    /** 凭据缺失必须 fail-closed 暴露失败，绝不得冒用设备 Token 发请求。 */
    @Test
    void reportsMissingManagementCredentialWithoutCallingEmqx() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        when(repository.findActiveMqttSessionIds(projectId, deviceId)).thenReturn(List.of("client-a"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmqxHttpDeviceSessionTerminator terminator = new EmqxHttpDeviceSessionTerminator(
                repository, () -> { throw new AssertionError("missing credentials must not create HTTP client"); },
                "", "", new DeviceSessionTerminationMetrics(registry));

        terminator.disconnectAfterCommit(projectId, deviceId);

        assertThat(registry.get(DeviceSessionTerminationMetrics.ATTEMPTS)
                .tag("result", "credentials_missing").counter().count()).isEqualTo(1.0);
    }

    /** 回滚的凭据事实不能踢出原本合法的设备会话。 */
    @Test
    void skipsTerminationWhenCredentialTransactionRollsBack() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmqxHttpDeviceSessionTerminator terminator = new EmqxHttpDeviceSessionTerminator(
                repository, () -> { throw new AssertionError("rollback must not create HTTP client"); },
                "session-key", "session-secret", new DeviceSessionTerminationMetrics(registry));
        TransactionSynchronizationManager.initSynchronization();
        try {
            terminator.disconnectAfterCommit(projectId, deviceId);
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(repository).findActiveMqttSessionIds(projectId, deviceId);
    }

    /** 提交前不能观察 Broker 副作用，提交完成后必须立即执行。 */
    @Test
    void terminatesOnlyAfterCredentialTransactionCommits() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        when(repository.findActiveMqttSessionIds(projectId, deviceId)).thenReturn(List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmqxHttpDeviceSessionTerminator terminator = new EmqxHttpDeviceSessionTerminator(
                repository, () -> { throw new AssertionError("empty session list must not create HTTP client"); },
                "session-key", "session-secret", new DeviceSessionTerminationMetrics(registry));
        TransactionSynchronizationManager.initSynchronization();
        try {
            terminator.disconnectAfterCommit(projectId, deviceId);
            verify(repository).findActiveMqttSessionIds(projectId, deviceId);
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(repository).findActiveMqttSessionIds(projectId, deviceId);
    }

    /** 控制事务关闭全部行后，提交回调仍踢原快照，不能重新查空集。 */
    @Test
    void retainsCapturedIdsAfterFactsClose() {
        UUID project = UUID.randomUUID(), device = UUID.randomUUID();
        DeviceConnectionRepository repository = mock(DeviceConnectionRepository.class);
        when(repository.findActiveMqttSessionIds(project, device)).thenReturn(List.of("old-client"), List.of());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://emqx:18083/api/v5/clients/old-client"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        var terminator = terminator(repository, builder, new SimpleMeterRegistry(), "key", "secret");
        TransactionSynchronizationManager.initSynchronization();
        try {
            terminator.disconnectAfterCommit(project, device);
            verify(repository).findActiveMqttSessionIds(project, device);
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        verify(repository, org.mockito.Mockito.times(1)).findActiveMqttSessionIds(project, device);
        server.verify();
    }

    /** 绑定 mock 请求工厂并创建待测适配器。 */
    private static EmqxHttpDeviceSessionTerminator terminator(
            DeviceConnectionRepository repository, RestClient.Builder builder,
            SimpleMeterRegistry registry, String apiKey, String apiSecret) {
        return new EmqxHttpDeviceSessionTerminator(repository,
                () -> builder.baseUrl("http://emqx:18083").build(), apiKey, apiSecret,
                new DeviceSessionTerminationMetrics(registry));
    }
}
