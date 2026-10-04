package com.things.link.ota.application;

import com.things.link.ota.domain.OtaReleaseDownloadErrorCode;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 签发策略及二次资格的纯端口测试，不冒充真实网络/数据库与幂等过滤器验收。 */
class OtaReleaseDownloadIssuerTests {
    /** 固定项目入口。 */
    private final UUID project = UUID.randomUUID();
    /** 固定固件入口。 */
    private final UUID firmware = UUID.randomUUID();
    /** 前后事务端口替身。 */
    private final OtaReleaseDownloadService service = mock(OtaReleaseDownloadService.class);
    /** 技术签发端口替身。 */
    private final VersionedPrivateObjectStorage storage = mock(VersionedPrivateObjectStorage.class);
    /** 每次返回新流，不能复用已消费Stream。 */
    @SuppressWarnings("unchecked")
    private final ObjectProvider<VersionedPrivateObjectStorage> storages = mock(ObjectProvider.class);
    /** 初次资格所得同一快照传给confirm，不包含生成URL。 */
    private OtaReleaseDownloadService.Snapshot snapshot;
    /** 默认拒绝明文URL的受测服务。 */
    private OtaReleaseDownloadIssuer issuer;

    /** 固定对象身份，其他业务资格由service独立集成测试负责。 */
    @BeforeEach
    void setup() {
        OtaUploadSession upload = mock(OtaUploadSession.class);
        when(upload.bucket()).thenReturn("private-test"); when(upload.objectKey()).thenReturn("exact/key");
        when(upload.versionId()).thenReturn("immutable-version");
        snapshot = new OtaReleaseDownloadService.Snapshot(null, upload, null, null, null, UUID.randomUUID());
        when(service.read(project, firmware)).thenReturn(snapshot);
        when(storages.orderedStream()).thenAnswer(call -> Stream.of(storage));
        issuer = new OtaReleaseDownloadIssuer(service, storages, false);
    }
    /** 清除中断测试留下的标志，避免污染JUnit工作线程。 */
    @AfterEach
    void clearInterrupt() { Thread.interrupted(); }

    /** 固定60秒和精确版本传给端口，每次重新签发并复核，不缓存先前URL。 */
    @Test
    void signsExactVersionForSixtySecondsAndDoesNotCacheUrls() {
        URI first = URI.create("https://storage.example/object?signature=first");
        URI second = URI.create("https://storage.example/object?signature=second");
        when(storage.presignGet(any(), any(), any())).thenReturn(first, second);
        Instant start = Instant.now();
        var one = issuer.issue(project, firmware);
        var two = issuer.issue(project, firmware);
        assertThat(one.downloadUrl()).isEqualTo(first);
        assertThat(two.downloadUrl()).isEqualTo(second);
        assertThat(one.expiresAt()).isBetween(start.truncatedTo(ChronoUnit.SECONDS).plusSeconds(60),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60));
        assertThat(one.toString()).doesNotContain("signature", "storage.example", first.toString());
        verify(storage, times(2)).presignGet(eq(new VersionedPrivateObjectStorage.VersionRef(
                "private-test", "exact/key", "immutable-version")), eq(Duration.ofSeconds(60)), any());
        verify(service, times(2)).read(project, firmware);
        verify(service, times(2)).confirm(snapshot);
    }

    /** 默认HTTPS也禁止userinfo/fragment/空host，不返回不安全地址或调用最终审计。 */
    @Test
    void rejectsUnsafeUrlFormsByDefault() {
        for (String text : List.of("http://localhost/object", "http://127.0.0.1/object",
                "https://user:secret@storage.example/object", "https://storage.example/object#token",
                "https:/object", "/relative", "ftp://storage.example/object")) {
            when(storage.presignGet(any(), any(), any())).thenReturn(URI.create(text));
            assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class).hasNoCause();
        }
        when(storage.presignGet(any(), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        verify(service, never()).confirm(any());
    }

    /** 显式开发配置只允许精确回环，不能允许相似主机、DNS后缀或其他私网地址。 */
    @Test
    void explicitLoopbackAllowsOnlyExactHosts() {
        issuer = new OtaReleaseDownloadIssuer(service, storages, true);
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            URI uri = URI.create("http://" + host + ":9000/object");
            when(storage.presignGet(any(), any(), any())).thenReturn(uri);
            assertThat(issuer.issue(project, firmware).downloadUrl()).isEqualTo(uri);
        }
        for (String host : List.of("localhost.attacker.test", "localhost.", "127.0.0.2", "127.1", "192.168.1.2")) {
            when(storage.presignGet(any(), any(), any())).thenReturn(URI.create("http://" + host + "/object"));
            assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        }
        verify(service, times(3)).confirm(snapshot);
    }

    /** URL已生成但二次资格失败时整个调用抛错；后续请求重读且重新签发。 */
    @Test
    void secondQualificationFailureNeverReturnsOrReusesGeneratedUrl() {
        when(storage.presignGet(any(), any(), any())).thenReturn(URI.create("https://storage.example/?secret=ephemeral"));
        doThrow(new BusinessException(OtaReleaseDownloadErrorCode.INELIGIBLE)).when(service).confirm(snapshot);
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        verify(storage, times(2)).presignGet(any(), any(), any());
        verify(service, times(2)).read(project, firmware);
    }

    /** 即使故障端口迟到返回URL，5秒技术预算耗尽后也不能进入最终资格或返回。 */
    @Test
    void rejectsLateProviderReturnAfterTechnicalBudget() {
        when(storage.presignGet(any(), any(), any())).thenAnswer(call -> {
            VersionedStorageControl control = call.getArgument(2);
            assertThat(control.remainingNanos()).isBetween(1L, Duration.ofSeconds(5).toNanos());
            Thread.sleep(5_100);
            return URI.create("https://storage.example/?signature=late");
        });
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class).hasNoCause();
        verify(service, never()).confirm(any());
    }

    /** watchdog异线程观察的取消源必须仍指向签发调用者，不能误读自身线程中断。 */
    @Test
    void cancellationObserverOnAnotherThreadSeesCallerInterruption() {
        when(storage.presignGet(any(), any(), any())).thenAnswer(call -> {
            VersionedStorageControl control = call.getArgument(2);
            Thread caller = Thread.currentThread();
            AtomicBoolean observed = new AtomicBoolean();
            AtomicReference<Boolean> cancelled = new AtomicReference<>();
            Thread.ofPlatform().start(() -> {
                caller.interrupt();
                cancelled.set(control.cancelled());
                observed.set(true);
            });
            // 不使用join/get/park：它们可能暂时消费当前线程中断，制造不存在的false窗口。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!observed.get() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(observed.get()).isTrue();
            assertThat(cancelled.get()).isTrue();
            assertThat(caller.isInterrupted()).isTrue();
            return URI.create("https://storage.example/?signature=cancelled");
        });
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        verify(service, never()).confirm(any());
    }

    /** 缺失或歧义配置失败关闭，供应商异常正文不会带入对外异常链。 */
    @Test
    void rejectsMissingAmbiguousStorageAndRedactsProviderFailures() {
        when(storages.orderedStream()).thenAnswer(call -> Stream.empty());
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        when(storages.orderedStream()).thenAnswer(call -> Stream.of(storage, storage));
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class);
        when(storages.orderedStream()).thenAnswer(call -> Stream.of(storage));
        when(storage.presignGet(any(), any(), any())).thenThrow(new IllegalStateException("https://secret?credential=private"));
        assertThatThrownBy(() -> issuer.issue(project, firmware)).isInstanceOf(BusinessException.class)
                .hasNoCause().hasMessageNotContaining("credential");
        verify(service, never()).confirm(any());
    }
}
