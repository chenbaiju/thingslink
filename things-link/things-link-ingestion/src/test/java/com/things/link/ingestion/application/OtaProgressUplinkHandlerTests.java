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

import com.things.link.ota.application.OtaJobProgressIngestionService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.RawUplinkMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** MQTT精确分流及原认证代际传递，不将基建失败变为永久拒绝。 */
class OtaProgressUplinkHandlerTests {
    /** 服务mock不代替真实数据库授权。 */
    private final OtaJobProgressIngestionService service = mock(OtaJobProgressIngestionService.class);
    /** 实际分流器。 */
    private final OtaProgressUplinkHandler handler = new OtaProgressUplinkHandler(service);

    /** 独立进度后缀完整识别，认证四轴与平台时间不补查、不改写。 */
    @Test void passesOriginalIdentityAndReceiveTime() {
        var raw = raw("up/ota/progress", true);
        assertTrue(handler.tryAccept(raw));
        verify(service).accept(eq(raw.authenticatedIdentity()), eq(raw.payload()), eq(raw.receivedAt()));
        var legacy = raw("up/ota/progress", false);
        assertTrue(handler.tryAccept(legacy));
        verify(service).accept(isNull(), eq(legacy.payload()), eq(legacy.receivedAt()));
    }

    /** 相邻协议、下行和非法路径不进入进度服务。 */
    @Test void ignoresOtherAndMalformedRoutes() {
        for (String suffix : new String[] {"up/ota/report", "up/ota/progress/extra",
                "down/ota/progress", "up/ota//request", "up/ota/progres"}) {
            assertFalse(handler.tryAccept(raw(suffix, true)));
        }
        verifyNoInteractions(service);
    }

    /** 永久拒绝分类固定，数据库失败保留原异常供基础设施重试。 */
    @Test void separatesPermanentContractFailureFromTransientDatabaseFailure() {
        when(service.accept(any(), any(), any())).thenThrow(new IllegalArgumentException("不可信正文"));
        assertThrows(InvalidUplinkMessageException.class,
                () -> handler.tryAccept(raw("up/ota/progress", true)));
        var other = mock(OtaJobProgressIngestionService.class);
        var failure = new DataAccessResourceFailureException("测试数据库中断");
        when(other.accept(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new OtaProgressUplinkHandler(other).tryAccept(raw("up/ota/progress", true))));
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
