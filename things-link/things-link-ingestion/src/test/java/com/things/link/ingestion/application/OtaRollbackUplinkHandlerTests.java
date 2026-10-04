package com.things.link.ingestion.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.ota.application.OtaRollbackIngestionService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.RawUplinkMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** MQTT精确分流及原认证代际传递，不将基建失败变为永久拒绝。 */
class OtaRollbackUplinkHandlerTests {
    /** 服务mock不代替真实数据库授权。 */
    private final OtaRollbackIngestionService service = mock(OtaRollbackIngestionService.class);
    /** 实际分流器。 */
    private final OtaRollbackUplinkHandler handler = new OtaRollbackUplinkHandler(service);

    /** 独立回退执行后缀完整识别，认证四轴与平台时间不补查、不改写。 */
    @Test void passesOriginalIdentityAndReceiveTime() {
        var raw = raw("up/ota/rollback/operation/report", true);
        assertTrue(handler.tryAccept(raw));
        verify(service).acceptOperation(eq(raw.authenticatedIdentity()), eq(raw.payload()), eq(raw.receivedAt()));
        var legacy = raw("up/ota/rollback/operation/report", false);
        assertTrue(handler.tryAccept(legacy));
        verify(service).acceptOperation(isNull(), eq(legacy.payload()), eq(legacy.receivedAt()));
        var status = raw("up/ota/rollback/status/report", true);
        assertTrue(handler.tryAccept(status));
        verify(service).acceptStatus(eq(status.authenticatedIdentity()), eq(status.payload()), eq(status.receivedAt()));
    }

    /** 相邻协议、下行和非法路径不进入回退执行服务。 */
    @Test void ignoresOtherAndMalformedRoutes() {
        for (String suffix : new String[] {"up/ota/rollback/preflight/report", "up/ota/rollback/status/report/extra", "up/ota/health", "up/ota/progress", "up/ota/report", "up/ota/rollback/operation/report/extra",
                "down/ota/rollback/operation/report", "up/ota//request", "up/ota/progres"}) {
            assertFalse(handler.tryAccept(raw(suffix, true)));
        }
        verifyNoInteractions(service);
    }

    /** 永久拒绝分类固定，数据库失败保留原异常供基础设施重试。 */
    @Test void separatesPermanentContractFailureFromTransientDatabaseFailure() {
        when(service.acceptOperation(any(), any(), any())).thenThrow(new IllegalArgumentException("不可信正文"));
        assertThrows(InvalidUplinkMessageException.class,
                () -> handler.tryAccept(raw("up/ota/rollback/operation/report", true)));
        var other = mock(OtaRollbackIngestionService.class);
        var failure = new DataAccessResourceFailureException("测试数据库中断");
        when(other.acceptOperation(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new OtaRollbackUplinkHandler(other).tryAccept(raw("up/ota/rollback/operation/report", true))));
        when(service.acceptStatus(any(), any(), any())).thenThrow(new IllegalArgumentException("不可信状态正文"));
        assertThrows(InvalidUplinkMessageException.class,
                () -> handler.tryAccept(raw("up/ota/rollback/status/report", true)));
        when(other.acceptStatus(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new OtaRollbackUplinkHandler(other).tryAccept(raw("up/ota/rollback/status/report", true))));
    }

    /** 真实共享信封保持原有认证代际约束。 */
    private static RawUplinkMessage raw(String suffix, boolean authenticated) {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        var identity = authenticated ? new AuthenticatedDeviceIdentity(tenant, project, device, 17) : null;
        return new RawUplinkMessage(tenant, project, device, "tc/v1/project/device/" + suffix,
                new byte[] {1}, 1, false, "test", Instant.now(), "trace", identity);
    }
}
