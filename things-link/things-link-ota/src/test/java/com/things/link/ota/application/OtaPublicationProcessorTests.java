package com.things.link.ota.application;

import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实工作线程与取消控制配合持久端口替身，验证物理容量及结果未知恢复。 */
class OtaPublicationProcessorTests {
    /** 独立短事务端口替身。 */
    private final OtaPublicationService service = mock(OtaPublicationService.class);
    /** 当前信任由service.binding显式提供，处理器不使用全局成功配置。 */
    private final OtaTrustService trust = mock(OtaTrustService.class);
    /** 对象入口替身。 */
    private final OtaUploadService uploads = mock(OtaUploadService.class);
    /** 精确版本读取替身，不冒称真实MinIO。 */
    private final VersionedPrivateObjectStorage storage = mock(VersionedPrivateObjectStorage.class);
    /** 被测自有线程组件。 */
    private OtaPublicationProcessor processor;
    /** 容量测试阻挡真实signer返回，失败清理时必释放。 */
    private final CountDownLatch release = new CountDownLatch(1);

    /** 开启本组件生命周期和固定对象入口。 */
    @BeforeEach
    void start() {
        processor = new OtaPublicationProcessor(service, trust, uploads, true);
        processor.start();
        when(uploads.requireStorage()).thenReturn(storage);
        when(service.renew(any())).thenReturn(true);
    }
    /** 不把测试阻挡遗留到下一例，清除调用线程范围。 */
    @AfterEach
    void stop() { release.countDown(); processor.stop(); TenantContext.clear(); }

    /** 两个签名物理阻塞时不能第三次领取，stop发取消后仍等待实际调用退出。 */
    @Test
    void holdsTwoSlotsUntilSignersActuallyExitAndRestoresWorkerScope() throws Exception {
        OtaPublication first = publication("SIGNING");
        OtaPublication second = publication("SIGNING");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch cancellations = new CountDownLatch(2);
        AtomicReference<Throwable> assertionFailure = new AtomicReference<>();
        when(service.claim()).thenReturn(Optional.of(first), Optional.of(second));
        when(service.prepare(any())).thenAnswer(call -> context(call.getArgument(0)));
        when(service.reread(any())).thenAnswer(call -> call.getArgument(0));
        when(service.binding(any(), anyString())).thenReturn(binding());
        when(service.requireSigner()).thenReturn((request, control) -> {
            try {
                assertThat(TenantContext.require().accountId()).isEqualTo(first.createdBy());
                try (var registration = control.onCancel(cancellations::countDown)) {
                    entered.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("fixture release missing");
                    throw new IllegalStateException("provider result unknown");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("fixture interrupted");
            } catch (AssertionError failure) {
                assertionFailure.set(failure); throw failure;
            }
        });
        processor.tick(); processor.tick();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        processor.tick();
        verify(service, times(2)).claim();
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(processor::stop);
        assertThat(cancellations.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stopped.isDone()).isFalse();
        AtomicInteger closeCallbacks = new AtomicInteger();
        assertThatThrownBy(() -> processor.stop(closeCallbacks::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
        assertThat(closeCallbacks).hasValue(0);
        assertThat(assertionFailure.get()).isNull();
        release.countDown();
        stopped.get(5, TimeUnit.SECONDS);
        processor.stop(closeCallbacks::incrementAndGet);
        assertThat(closeCallbacks).hasValue(1);
        verify(service, times(2)).failed(any(), org.mockito.ArgumentMatchers.eq(true), anyString());
    }

    /** signed事务已提交但响应未知，权威SIGNED必须保留恢复，不能退为REJECTED或重签。 */
    @Test
    void preservesSignedWhenSignaturePersistenceResponseIsUnknown() throws Exception {
        OtaPublication signing = publication("SIGNING");
        OtaPublication signed = withStatus(signing, "SIGNED");
        OtaPublicationService.Context signingContext = context(signing);
        when(service.prepare(signing)).thenReturn(signingContext);
        when(service.binding(any(), anyString())).thenReturn(binding());
        when(service.requireSigner()).thenReturn((request, control) -> response(request));
        when(service.signed(any(), any())).thenThrow(new IllegalStateException("transaction response lost"));
        when(service.reread(signing)).thenReturn(signed);
        processor.process(signing);
        verify(service, never()).failed(any(), anyBoolean(), anyString());
        verify(service, never()).commit(any());
    }

    /** 已收到真实有效签名但保存失败且权威仍SIGNING，只能UNKNOWN，不能开放新尝试重签。 */
    @Test
    void signedResponseWithoutDurableReceiptRemainsUnknownAndSignsOnlyOnce() throws Exception {
        OtaPublication signing = publication("SIGNING");
        OtaPublicationService.Context signingContext = context(signing);
        AtomicInteger calls = new AtomicInteger();
        when(service.prepare(signing)).thenReturn(signingContext);
        when(service.binding(any(), anyString())).thenReturn(binding());
        when(service.requireSigner()).thenReturn((request, control) -> {
            calls.incrementAndGet();
            return response(request);
        });
        when(service.signed(any(), any())).thenThrow(new DataAccessResourceFailureException("receipt write unavailable"));
        when(service.reread(signing)).thenReturn(signing);
        processor.process(signing);
        assertThat(calls).hasValue(1);
        verify(service, times(1)).signed(org.mockito.ArgumentMatchers.eq(signing), any());
        verify(service).failed(org.mockito.ArgumentMatchers.eq(signing), org.mockito.ArgumentMatchers.eq(true), anyString());
        verify(service, never()).failed(any(), org.mockito.ArgumentMatchers.eq(false), anyString());
        verify(service, never()).commit(any());
    }

    /** 最终提交抛错但权威COMMITTED时，不覆盖终态、不再签名或清理对象。 */
    @Test
    void committedRereadWinsOverAmbiguousCommitException() throws Exception {
        OtaPublication signed = publication("SIGNED");
        OtaPublicationService.Context signedContext = context(signed);
        when(service.prepare(signed)).thenReturn(signedContext);
        when(service.commit(signed)).thenThrow(new IllegalStateException("commit response lost"));
        when(service.reread(signed)).thenReturn(withStatus(signed, "COMMITTED"));
        processor.process(signed);
        verify(service).commit(signed);
        verify(service, never()).requireSigner();
        verify(service, never()).failed(any(), anyBoolean(), anyString());
        verify(storage, never()).delete(any(), any());
    }

    /** UNKNOWN没有可执行领取token，不能因重复调用处理入口重发签名。 */
    @Test
    void unknownWithoutLeaseNeverCallsSigner() throws Exception {
        OtaPublication base = publication("UNKNOWN");
        OtaPublication unknown = new OtaPublication(base.id(), base.tenantId(), base.projectId(), base.firmwareId(),
                base.uploadSessionId(), base.createdBy(), base.requestId(), 1, 0, 2, base.canonicalManifest(),
                base.trustSnapshot(), "UNKNOWN", 1, null, null, null, "SIGNER_UNAVAILABLE", null, null,
                base.createdAt(), base.updatedAt());
        processor.process(unknown); processor.process(unknown);
        verify(service, never()).prepare(any());
        verify(service, never()).requireSigner();
    }

    /** 处理器所需固定上传字段；业务真实性另由service真实集成测试验证。 */
    private static OtaPublicationService.Context context(OtaPublication publication) {
        OtaUploadSession upload = mock(OtaUploadSession.class);
        when(upload.bucket()).thenReturn("private-test"); when(upload.objectKey()).thenReturn("exact/key");
        when(upload.versionId()).thenReturn("fixed-version"); when(upload.expectedLength()).thenReturn(1024L);
        when(upload.expectedSha256()).thenReturn("a".repeat(64));
        return new OtaPublicationService.Context(null, upload, publication, null);
    }
    /** 固定公开manifest及范围用于线程身份检查。 */
    private static OtaPublication publication(String status) throws Exception {
        UUID scope = UUID.fromString("018f0000-0000-7000-8000-000000000010");
        return new OtaPublication(UUID.randomUUID(), scope, scope, scope, scope, scope, UUID.randomUUID(),
                1, 0, 2, resource("manifest-v1.json"), new byte[] {1}, status, 1, null, null, null, null,
                UUID.randomUUID(), Instant.now().plusSeconds(120), Instant.EPOCH, Instant.EPOCH);
    }
    /** 只改变权威回读阶段，保持原请求和租约身份。 */
    private static OtaPublication withStatus(OtaPublication p, String status) {
        return new OtaPublication(p.id(), p.tenantId(), p.projectId(), p.firmwareId(), p.uploadSessionId(), p.createdBy(),
                p.requestId(), p.projectGeneration(), p.firmwareRevision(), p.uploadRevision(), p.canonicalManifest(),
                p.trustSnapshot(), status, p.revision() + 1, p.spki(), p.signature(), p.receipt(), p.failureCode(),
                p.leaseToken(), p.leaseUntil(), p.createdAt(), p.updatedAt());
    }
    /** 固定公开向量的可信测试绑定。 */
    private static OtaSigningTrustSource.Binding binding() {
        return new OtaSigningTrustSource.Binding("test.example", "test:key/1", OtaSignatureProfile.ED25519_V1,
                "b4eddd0f449a035411298999ebbba43a405c3412044f070ea7d9f8a48c7b7a48", 1);
    }
    /** 只返回已经存在的公开向量，测试不生成或持有签名私钥。 */
    private static OtaReleaseSigner.Response response(OtaReleaseSigner.Request request) {
        try {
            return new OtaReleaseSigner.Response(request.requestId(), request.keyVersion(), request.profile(),
                    hex("manifest-v1.spki.hex"), hex("manifest-v1.signature.hex"), "receipt:1");
        } catch (Exception failure) { throw new IllegalStateException("缺少公开fixture"); }
    }
    /** 读取公开签名/公钥字节。 */
    private static byte[] hex(String name) throws Exception { return HexFormat.of().parseHex(new String(resource(name), StandardCharsets.US_ASCII).trim()); }
    /** 固定向量缺失立即失败。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaPublicationProcessorTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("缺少公开fixture");
            return input.readAllBytes();
        }
    }
}
