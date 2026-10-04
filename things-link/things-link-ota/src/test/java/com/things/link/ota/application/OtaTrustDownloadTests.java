package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;

import com.things.link.ota.domain.OtaTrustRepository;
import com.things.link.ota.domain.OtaTrustState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理下载资格使用真实根验签公开向量，仓储替身只提供当前域事实。 */
class OtaTrustDownloadTests {
    /** 固定受控租户。 */
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    /** 固定受控项目。 */
    private static final UUID PROJECT = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    /** 固定授权类型。 */
    private static final UUID TYPE = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    /** 精确当前事实替身，不替代密码学校验。 */
    private final OtaTrustRepository repository = mock(OtaTrustRepository.class);
    /** 管理成员资格替身。 */
    private final ProjectService projects = mock(ProjectService.class);
    /** 受测服务。 */
    private OtaTrustService service;
    /** 真实解析受控根。 */
    private OtaTrustAnchors.Anchor root;
    /** 请求的原发布键指纹。 */
    private String fingerprint;

    /** 从公开固定根及包建立本例，不在运行时生成私钥。 */
    @BeforeEach
    void setup() throws Exception {
        OtaTrustAnchors anchors = new OtaTrustAnchors(new String(resource("anchors.json"), StandardCharsets.UTF_8));
        root = anchors.require(TENANT, PROJECT, "test.domain");
        fingerprint = bundle("active").keys().getFirst().fingerprint();
        service = new OtaTrustService(repository, anchors, projects, mock(ProjectLifecycleAccessService.class),
                mock(AuditLogService.class), mock(OtaCampaignRuntimeRepository.class));
        when(projects.requireRoleInProject(PROJECT)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(PROJECT)).thenReturn(TENANT);
    }

    /** 当前ACTIVE和轮换后的VERIFY_ONLY都选中原键，返回的是当前修订而非旧发布修订。 */
    @Test
    void acceptsActiveAndVerifyOnlyOriginalKeyWithCurrentRevision() throws Exception {
        for (String name : List.of("active", "verify-only")) {
            OtaTrustState state = state(name, root.policyHash(), false);
            when(repository.find(PROJECT, "test.domain", false, true)).thenReturn(Optional.of(state));
            var grant = download();
            assertThat(grant.binding().keyVersion()).isEqualTo("original:1");
            assertThat(grant.binding().fingerprint()).isEqualTo(fingerprint);
            assertThat(grant.binding().revision()).isEqualTo(state.revision());
            assertThat(grant.bundleVersion()).isEqualTo(state.bundleVersion());
        }
        // 发布入口仍只选择当前ACTIVE新键，不扩大到VERIFY_ONLY。
        when(repository.find(PROJECT, "test.domain", false, false))
                .thenReturn(Optional.of(state("verify-only", root.policyHash(), false)));
        assertThat(service.activeKey(PROJECT, TYPE, "test.domain").binding().keyVersion()).isEqualTo("next:1");
    }

    /** PREPARED/REVOKED及过期/未来有效的原键均拒绝，不改用包里的另一ACTIVE键。 */
    @Test
    void rejectsDisallowedStatesAndValidityWithoutFallback() throws Exception {
        for (String name : List.of("prepared", "revoked", "expired", "future")) {
            when(repository.find(PROJECT, "test.domain", false, true))
                    .thenReturn(Optional.of(state(name, root.policyHash(), false)));
            assertThatThrownBy(this::download).isInstanceOf(BusinessException.class);
        }
    }

    /** 当前policy不匹配或原始根签名被损坏时，不能仅信数据库状态字段。 */
    @Test
    void rejectsPolicyMismatchAndTamperedStoredSignature() throws Exception {
        when(repository.find(PROJECT, "test.domain", false, true))
                .thenReturn(Optional.of(state("verify-only", "0".repeat(64), false)));
        assertThatThrownBy(this::download).isInstanceOf(BusinessException.class);
        when(repository.find(PROJECT, "test.domain", false, true))
                .thenReturn(Optional.of(state("verify-only", root.policyHash(), true)));
        assertThatThrownBy(this::download).isInstanceOf(BusinessException.class);
    }

    /** 精确版本/Profile/指纹和受控类型不能由下载请求替换，普通成员也不得取得资格。 */
    @Test
    void rejectsIdentityScopeAndNonManager() throws Exception {
        when(repository.find(PROJECT, "test.domain", false, true))
                .thenReturn(Optional.of(state("active", root.policyHash(), false)));
        assertThatThrownBy(() -> service.lockForDownload(PROJECT, TYPE, "test.domain", "missing",
                OtaSignatureProfile.ED25519_V1, fingerprint)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.lockForDownload(PROJECT, TYPE, "test.domain", "original:1",
                OtaSignatureProfile.ES256_P1363_V1, fingerprint)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.lockForDownload(PROJECT, TYPE, "test.domain", "original:1",
                OtaSignatureProfile.ED25519_V1, "0".repeat(64))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.lockForDownload(PROJECT, UUID.randomUUID(), "test.domain", "original:1",
                OtaSignatureProfile.ED25519_V1, fingerprint)).isInstanceOf(BusinessException.class);
        when(projects.requireRoleInProject(PROJECT)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(this::download).isInstanceOf(BusinessException.class);
    }

    /** 下载入口声明强制已有事务，并向仓储要求域共享锁；真实PG互斥由集成测试证明。 */
    @Test
    void requiresExistingTransactionAndSharedDomainLock() throws Exception {
        var method = OtaTrustService.class.getMethod("lockForDownload", UUID.class, UUID.class, String.class,
                String.class, OtaSignatureProfile.class, String.class);
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        when(repository.find(PROJECT, "test.domain", false, true))
                .thenReturn(Optional.of(state("active", root.policyHash(), false)));
        download();
        verify(repository).find(PROJECT, "test.domain", false, true);
    }

    /** 请求精确原发布身份，旧发布修订不作为当前域选择条件。 */
    private OtaTrustService.Grant download() {
        return service.lockForDownload(PROJECT, TYPE, "test.domain", "original:1", OtaSignatureProfile.ED25519_V1, fingerprint);
    }
    /** 构造当前指针与不可变签名包一致的事实，篡改用单独开关。 */
    private OtaTrustState state(String name, String policyHash, boolean tamper) throws Exception {
        var bundle = bundle(name);
        byte[] signature = signature(name);
        if (tamper) signature[0] ^= 1;
        return new OtaTrustState(TENANT, PROJECT, "test.domain", root.rootProfile().value(), root.rootFingerprint(),
                root.policyRevision(), policyHash, bundle.bundleVersion(), bundle.bundleVersion(), bundle.sha256(), bundle.canonical(),
                signature, Instant.EPOCH, Instant.EPOCH);
    }
    /** 成功素材先用真实Codec验根，不从测试伪造密钥状态跳过签名。 */
    private OtaTrustBundleCodec.Bundle bundle(String name) throws Exception {
        return new OtaTrustBundleCodec().verify(resource(name + ".json"), signature(name), root);
    }
    /** 读取公开固定签名。 */
    private static byte[] signature(String name) throws Exception {
        return HexFormat.of().parseHex(new String(resource(name + ".sig.hex"), StandardCharsets.US_ASCII).trim());
    }
    /** 只读取已提交候选中的公开素材。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaTrustDownloadTests.class.getResourceAsStream("/ota/trust-download/" + name)) {
            if (input == null) throw new IllegalStateException("缺少公开下载信任向量");
            return input.readAllBytes();
        }
    }
}
