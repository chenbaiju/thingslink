package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 真实PG与完整Console安全链验证根签名导入、角色/隔离和公共完成墓碑；不调用生产signer。 */
@AutoConfigureMockMvc
class OtaTrustHttpIntegrationTests extends AbstractIntegrationTest {
    /** 配置根及发布公钥只属于本测试，私钥不离开测试内存。 */
    private static final KeyPair ROOT = pair(), FIRST = pair(), SECOND = pair();
    /** 启动配置必须预先知道固定测试scope，不由HTTP输入改变。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 独占域名，不与共享数据库其他夹具重复。 */
    private static final String DOMAIN = "http-" + UUID.randomUUID();
    /** 所有签名都来自真实规范字节。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 只读取真实HTTP响应。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 本例所有者图。 */ private final List<Fixture> fixtures = new ArrayList<>();
    /** 完整安全链及公共幂等。 */ @Autowired private MockMvc mvc;
    /** 令牌经过真实验签而非模拟认证上下文。 */ @Autowired private TokenIssuer tokens;
    /** 真实代理资格服务，不能用repo替身证明MANDATORY。 */
    @Autowired private com.things.link.ota.application.OtaTrustService trust;
    /** 调用者实际业务事务。 */
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 当前普通APP物理连接用于取得锁持有者PID。 */
    @Autowired private JdbcTemplate jdbc;
    /** 多实例配置试验共享真实持久/权限/审计端口。 */
    @Autowired private com.things.link.ota.domain.OtaTrustRepository trustRepository;
    @Autowired private com.things.link.project.application.ProjectService projects;
    @Autowired private com.things.link.project.application.ProjectLifecycleAccessService lifecycle;
    @Autowired private com.things.link.support.audit.AuditLogService audit;
    /** 真实项目OTA控制锁，受控配置测试不能跳过该边界。 */
    @Autowired private com.things.link.ota.domain.OtaCampaignRuntimeRepository runtime;
    /** 仅公钥和授权范围进入启动配置。 */
    @DynamicPropertySource
    static void anchors(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.trust.anchors-json", () -> new String(CANONICAL.writeObject(Map.of(
                "anchors", List.of(Map.of("tenantId", CONFIGURED.tenantId().toString(),
                        "projectId", CONFIGURED.projectId().toString(), "trustDomain", DOMAIN,
                        "allowedDeviceTypeIds", List.of(CONFIGURED.typeId().toString()),
                        "rootProfile", "TC_OTA_ED25519_V1", "rootSpki", b64(ROOT.getPublic().getEncoded()),
                        "rootFingerprint", sha(ROOT.getPublic().getEncoded()), "policyRevision", 1L)))), StandardCharsets.UTF_8));
    }
    /** 首包、GET脱敏、重复墓碑和更高完整包状态演进均走HTTP事务。 */
    @Test
    void importsSignedBundlesReadsSafeProjectionAndAdvancesMonotonically() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        String key = UUID.randomUUID().toString();
        byte[] first = envelope("0", 1, false);
        assertThat(write(f, key, first).getResponse().getStatus()).isEqualTo(200);
        JsonNode state = ok(read(f, path(f)));
        assertThat(state.path("trustDomain").asText()).isEqualTo(DOMAIN);
        assertThat(state.path("bundleVersion").asText()).isEqualTo("1");
        assertThat(state.path("policyRevision").asText()).isEqualTo("1");
        assertThat(state.path("rootFingerprint").asText()).isEqualTo(sha(ROOT.getPublic().getEncoded()));
        for (String field : List.of("tenantId", "projectId", "canonicalBundle", "signature", "keys", "rootSpki", "policyHash")) {
            assertThat(state.has(field)).as(field).isFalse();
        }
        error(write(f, key, first), 409, 10014);
        String previous = state.path("revision").asText();
        JsonNode second = ok(write(f, UUID.randomUUID().toString(), envelope(previous, 2, true)));
        assertThat(second.path("bundleVersion").asText()).isEqualTo("2");
        assertThat(Long.parseLong(second.path("revision").asText())).isGreaterThan(Long.parseLong(previous));
        assertThat(ok(read(f, path(f)))).isEqualTo(second);
        assertThat(write(f, UUID.randomUUID().toString(), envelope(previous, 3, true)).getResponse().getStatus()).isEqualTo(409);
        assertThat(ok(read(f, path(f)))).isEqualTo(second);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isEqualTo(2);
    }
    /** 当前角色和真实项目约束不能被根签名或历史授权替代。 */
    @Test
    void enforcesAuthenticationMembershipScopeAndMandatoryKey() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        byte[] first = envelope("0", 1, false);
        assertThat(mvc.perform(post(path(f) + "/bundles").contentType("application/json").content(first))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(write(f, null, first).getResponse().getStatus()).isEqualTo(400);
        ok(write(f, UUID.randomUUID().toString(), first));
        Fixture other = seedOther(ProjectRole.OWNER);
        error(read(other, path(other)), 404, 70013);
        error(write(other, UUID.randomUUID().toString(), first), 503, 70010);
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", f.projectId(), f.accountId());
        ok(read(f, path(f)));
        error(write(f, UUID.randomUUID().toString(), envelope("1", 2, true)), 403, 70015);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isEqualTo(1);
    }
    /** 错误根签名和嵌套重复字段不产生任何域或历史记录。 */
    @Test
    void rejectsInvalidSignatureAndDuplicateNestedFieldsWithoutSideEffects() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        byte[] valid = envelope("0", 1, false);
        Map<String,Object> parsed = new java.util.LinkedHashMap<>(CANONICAL.parseObject(valid));
        parsed.put("signature", b64(new byte[64]));
        assertThat(write(f, UUID.randomUUID().toString(), CANONICAL.writeObject(parsed)).getResponse().getStatus()).isBetween(400, 499);
        byte[] duplicate = new String(valid, StandardCharsets.UTF_8).replace("\"bundleVersion\":1", "\"bundleVersion\":1,\"bundleVersion\":1")
                .getBytes(StandardCharsets.UTF_8);
        error(write(f, UUID.randomUUID().toString(), duplicate), 400, 10002);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_domain WHERE project_id=?", Long.class, f.projectId())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isZero();
    }
    /** 资格绑定真实项目/类型/精确版本，不能对任意类型返回同一个全局成功键。 */
    @Test
    void activeGrantBindsCurrentScopeAndRequiresOuterPublicationTransaction() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode imported = ok(write(f, UUID.randomUUID().toString(), envelope("0", 1, false)));
        var grant = scoped(f, () -> trust.activeKey(f.projectId(), f.typeId(), DOMAIN));
        assertThat(grant.projectId()).isEqualTo(f.projectId());
        assertThat(grant.tenantId()).isEqualTo(f.tenantId());
        assertThat(grant.deviceTypeId()).isEqualTo(f.typeId());
        assertThat(grant.bundleVersion()).isEqualTo(1);
        assertThat(grant.binding().fingerprint()).isEqualTo(sha(FIRST.getPublic().getEncoded()));
        assertThat(grant.binding().revision()).isEqualTo(Long.parseLong(imported.path("revision").asText()));
        assertThatThrownBy(() -> scoped(f, () -> trust.activeKey(f.projectId(), UUID.randomUUID(), DOMAIN)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70014));
        assertThatThrownBy(() -> scoped(f, () -> trust.lockForPublication(f.projectId(), f.typeId(), DOMAIN)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /** 允许登记根签名有效的未来/过期包，但不得回退选择仍在有效期的VERIFY_ONLY键。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void refusesInactiveTimeWindowWithoutSelectingVerifyOnlyKey(boolean future) throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        var active = new java.util.LinkedHashMap<>(key(SECOND, "second", "ACTIVE"));
        active.put("notBefore", future ? 253402300798L : 0L);
        active.put("notAfter", future ? 253402300799L : 1L);
        ok(write(f, UUID.randomUUID().toString(), customEnvelope("0", 1,
                List.of(key(FIRST, "first", "VERIFY_ONLY"), active))));
        assertThatThrownBy(() -> scoped(f, () -> trust.activeKey(f.projectId(), f.typeId(), DOMAIN)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70014));
        assertThatThrownBy(() -> scoped(f, () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> trust.lockForPublication(f.projectId(), f.typeId(), DOMAIN))))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70014));
    }

    /** 根签名合法也不能改写历史身份、有效期、删除键或将ACTIVE退回PREPARED。 */
    @Test
    void rejectsSignedHistoryRewritesAndRevokedResurrectionAtomically() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode first = ok(write(f, UUID.randomUUID().toString(), envelope("0", 1, false)));
        String revision = first.path("revision").asText();
        var changedTime = new java.util.LinkedHashMap<>(key(FIRST, "first", "VERIFY_ONLY"));
        changedTime.put("notAfter", 253402300798L);
        KeyPair replacement = pair();
        List<List<Map<String,Object>>> invalid = List.of(
                List.of(key(FIRST, "first", "PREPARED"), key(SECOND, "second", "ACTIVE")),
                List.of(key(SECOND, "second", "ACTIVE")),
                List.of(key(replacement, "first", "VERIFY_ONLY"), key(SECOND, "second", "ACTIVE")),
                List.of(changedTime, key(SECOND, "second", "ACTIVE")));
        for (var keys : invalid) {
            error(write(f, UUID.randomUUID().toString(), customEnvelope(revision, 2, keys)), 409, 70012);
            assertThat(ok(read(f, path(f)))).isEqualTo(first);
            assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isEqualTo(1);
        }
        JsonNode revoked = ok(write(f, UUID.randomUUID().toString(), customEnvelope(revision, 2,
                List.of(key(FIRST, "first", "REVOKED"), key(SECOND, "second", "ACTIVE")))));
        error(write(f, UUID.randomUUID().toString(), customEnvelope(revoked.path("revision").asText(), 3,
                List.of(key(FIRST, "first", "ACTIVE"), key(SECOND, "second", "VERIFY_ONLY")))), 409, 70012);
        assertThat(ok(read(f, path(f)))).isEqualTo(revoked);
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isEqualTo(2);
    }

    /** service真实共享锁保持到调用者事务结束，PG明确阻塞证据先于释放和导入完成。 */
    @Test
    void publicationServiceLockBlocksConcurrentHigherBundleImportUntilCommit() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode first = ok(write(f, UUID.randomUUID().toString(), envelope("0", 1, false)));
        var input = new com.things.link.ota.api.OtaTrustRequestParser().parse(
                envelope(first.path("revision").asText(), 2, true));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var writerPid = new java.util.concurrent.atomic.AtomicInteger();
        var readerPid = new java.util.concurrent.atomic.AtomicInteger();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reader = executor.submit(() -> scoped(f, () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        var grant = trust.lockForPublication(f.projectId(), f.typeId(), DOMAIN);
                        readerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        entered.countDown();
                        await(release);
                        return grant;
                    })));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> scoped(f, () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        return trust.importBundle(f.projectId(), DOMAIN, UUID.randomUUID().toString(), input.expectedRevision(),
                                input.bundle(), input.signature());
                    })));
            boolean blocked = false;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (writerPid.get() != 0 && Boolean.TRUE.equals(owner().queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS (SELECT 1 FROM pg_stat_activity "
                                + "WHERE pid=? AND wait_event_type='Lock' AND query LIKE '%FOR UPDATE OF d%')",
                        Boolean.class, readerPid.get(), writerPid.get(), writerPid.get()))) {
                    blocked = true; break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertThat(blocked).as("PG必须证明导入域行更新被service持有的共享锁阻塞").isTrue();
            assertThat(writer.isDone()).isFalse();
            release.countDown();
            assertThat(reader.get(5, java.util.concurrent.TimeUnit.SECONDS).bundleVersion()).isEqualTo(1);
            assertThat(writer.get(5, java.util.concurrent.TimeUnit.SECONDS).bundleVersion()).isEqualTo(2);
            assertThat(scoped(f, () -> trust.activeKey(f.projectId(), f.typeId(), DOMAIN)).bundleVersion()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 同库两个不可变配置实例：更高policy显式导入后旧实例不能取得资格或继续改写。 */
    @Test
    void higherPolicyFencesOldInstanceAndRejectsSameRevisionHashOrRootChanges() throws Exception {
        Fixture f = seed(ProjectRole.ADMIN);
        JsonNode first = ok(write(f, UUID.randomUUID().toString(), envelope("0", 1, false)));
        UUID additionalType = Uuid7.generate();
        owner().update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'新增授权类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, additionalType, f.tenantId(), f.projectId(), "type_" + additionalType, "product_" + additionalType);
        var types = List.of(f.typeId(), additionalType);
        var sameRevision = configuredService(anchorJson(ROOT, "TC_OTA_ED25519_V1", 1, types));
        var higher = configuredService(anchorJson(ROOT, "TC_OTA_ED25519_V1", 2, types));
        var next = new com.things.link.ota.api.OtaTrustRequestParser().parse(
                envelope(first.path("revision").asText(), 2, true));
        assertThatThrownBy(() -> inTransaction(f, () -> sameRevision.activeKey(f.projectId(), additionalType, DOMAIN)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70010));
        assertThatThrownBy(() -> inTransaction(f, () -> sameRevision.importBundle(f.projectId(), DOMAIN,
                UUID.randomUUID().toString(), next.expectedRevision(), next.bundle(), next.signature())))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70012));
        assertThat(ok(read(f, path(f)))).isEqualTo(first);
        // 提高启动配置本身不产生资格，必须有显式更高bundle登记。
        assertThatThrownBy(() -> inTransaction(f, () -> higher.activeKey(f.projectId(), additionalType, DOMAIN)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70010));
        var second = inTransaction(f, () -> higher.importBundle(f.projectId(), DOMAIN, UUID.randomUUID().toString(),
                next.expectedRevision(), next.bundle(), next.signature()));
        var grant = inTransaction(f, () -> higher.activeKey(f.projectId(), additionalType, DOMAIN));
        assertThat(grant.deviceTypeId()).isEqualTo(additionalType);
        assertThat(grant.policyRevision()).isEqualTo(2);
        assertThat(grant.bundleVersion()).isEqualTo(2);
        assertThatThrownBy(() -> scoped(f, () -> trust.activeKey(f.projectId(), f.typeId(), DOMAIN)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70010));
        var third = new com.things.link.ota.api.OtaTrustRequestParser().parse(envelope(Long.toString(second.revision()), 3, true));
        assertThatThrownBy(() -> scoped(f, () -> trust.importBundle(f.projectId(), DOMAIN, UUID.randomUUID().toString(),
                third.expectedRevision(), third.bundle(), third.signature())))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70012));
        // 两种合法根配置均签署有效包；拒绝来自已登记根不可变，而非故意制造坏签名。
        KeyPair changedEdRoot = pair();
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair changedEcRoot = generator.generateKeyPair();
        for (KeyPair changedRoot : List.of(changedEdRoot, changedEcRoot)) {
            String profile = changedRoot == changedEcRoot ? "TC_OTA_ES256_P1363_V1" : "TC_OTA_ED25519_V1";
            var changed = configuredService(anchorJson(changedRoot, profile, 3, types));
            byte[] signed = signWithRoot(changedRoot, third.bundle());
            assertThatThrownBy(() -> inTransaction(f, () -> changed.importBundle(f.projectId(), DOMAIN,
                    UUID.randomUUID().toString(), third.expectedRevision(), third.bundle(), signed)))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70012));
        }
        assertThat(ok(read(f, path(f))).path("bundleVersion").asText()).isEqualTo("2");
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_trust_bundle WHERE project_id=?", Long.class, f.projectId())).isEqualTo(2);
    }
    /** 仅模拟不同进程的启动配置快照，仓储/授权/审计仍为相同真实Spring依赖。 */
    private com.things.link.ota.application.OtaTrustService configuredService(String configuration) {
        return new com.things.link.ota.application.OtaTrustService(trustRepository,
                new com.things.link.ota.application.OtaTrustAnchors(configuration), projects, lifecycle, audit, runtime);
    }
    /** 直接构造服务没有代理，测试显式保留真实APP事务而非依靠注解自调用。 */
    private <T> T inTransaction(Fixture f, java.util.function.Supplier<T> action) {
        return scoped(f, () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> action.get()));
    }
    /** 完整公开根配置，不反射修改已运行实例。 */
    private static String anchorJson(KeyPair root, String profile, long revision, List<UUID> types) {
        return new String(CANONICAL.writeObject(Map.of("anchors", List.of(Map.of(
                "tenantId", CONFIGURED.tenantId().toString(), "projectId", CONFIGURED.projectId().toString(),
                "trustDomain", DOMAIN, "allowedDeviceTypeIds", types.stream().map(UUID::toString).toList(),
                "rootProfile", profile, "rootSpki", b64(root.getPublic().getEncoded()),
                "rootFingerprint", sha(root.getPublic().getEncoded()), "policyRevision", revision)))), StandardCharsets.UTF_8);
    }
    /** 独立JCA生成合法双Profile根签名；EC显式规约low-S以免反例被格式边界提前截获。 */
    private static byte[] signWithRoot(KeyPair root, byte[] bundle) throws Exception {
        boolean ec = root.getPrivate() instanceof java.security.interfaces.ECPrivateKey;
        Signature signer = Signature.getInstance(ec ? "SHA256withECDSAinP1363Format" : "Ed25519");
        signer.initSign(root.getPrivate());
        signer.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signer.update(bundle);
        byte[] signature = signer.sign();
        if (ec) {
            var order = ((java.security.interfaces.ECPrivateKey) root.getPrivate()).getParams().getOrder();
            var s = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(signature, 32, 64));
            if (s.compareTo(order.shiftRight(1)) > 0) {
                byte[] low = order.subtract(s).toByteArray();
                java.util.Arrays.fill(signature, 32, 64, (byte) 0);
                int count = Math.min(low.length, 32);
                System.arraycopy(low, low.length - count, signature, 64 - count, count);
            }
        }
        return signature;
    }

    /** 测试线程也必须显式绑定真实已播种scope，离开后恢复原上下文。 */
    private static <T> T scoped(Fixture f, java.util.function.Supplier<T> work) {
        return com.things.link.support.tenant.ScopedTenantWork.call(
                new com.things.link.shared.tenant.TenantScope(f.tenantId(), f.projectId(), f.accountId()), work);
    }
    /** 有界闩锁防止失败路径遗留事务。 */
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("测试共享锁释放超时");
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    /** 根签名始终对应完整自定义包，用于合法签名但非法状态转移反例。 */
    private static byte[] customEnvelope(String revision, long version, List<Map<String,Object>> keys) throws Exception {
        Map<String,Object> bundle = Map.of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN,
                "bundleVersion", version, "keys", keys);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ROOT.getPrivate());
        signer.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signer.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(Map.of("expectedRevision", revision, "bundle", bundle, "signature", b64(signer.sign())));
    }

    /** 制造完整包并用离线根独立JCA签名；不借被测codec替代签名生成。 */
    private static byte[] envelope(String revision, long version, boolean rotate) throws Exception {
        Map<String,Object> bundle = Map.of("contractVersion", "tc-ota-trust-bundle/v1", "trustDomain", DOMAIN,
                "bundleVersion", version, "keys", rotate ? List.of(key(FIRST, "first", "VERIFY_ONLY"), key(SECOND, "second", "ACTIVE"))
                        : List.of(key(FIRST, "first", "ACTIVE")));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ROOT.getPrivate());
        signer.update("thingslink-ota-trust-bundle-v1\0".getBytes(StandardCharsets.UTF_8));
        signer.update(CANONICAL.writeObject(bundle));
        return CANONICAL.writeObject(Map.of("expectedRevision", revision, "bundle", bundle, "signature", b64(signer.sign())));
    }
    /** 固定有效区间，后续包不能改写旧key的不可变字段。 */
    private static Map<String,Object> key(KeyPair pair, String version, String state) {
        return Map.of("keyVersion", version, "signatureProfile", "TC_OTA_ED25519_V1", "spki", b64(pair.getPublic().getEncoded()),
                "fingerprint", sha(pair.getPublic().getEncoded()), "state", state, "notBefore", 0L, "notAfter", 253402300799L);
    }
    /** 独立生成临时测试密钥。 */
    private static KeyPair pair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 标准带填充Base64。 */ private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    /** 独立完整SHA256。 */ private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** 当前域完整路径。 */ private static String path(Fixture f) { return "/api/v1/projects/" + f.projectId() + "/ota/trust-domains/" + DOMAIN; }
    /** JWT完整Console链。 */
    private MvcResult request(Fixture f, MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.header("Authorization", "Bearer " + tokens.issue(new AuthenticatedPrincipal(
                f.accountId(), f.tenantId(), f.projectId())).value())).andReturn();
    }
    /** 写入保留精确原文。 */
    private MvcResult write(Fixture f, String key, byte[] body) throws Exception {
        var builder = post(path(f) + "/bundles").contentType("application/json").content(body);
        if (key != null) builder.header("Idempotency-Key", key);
        return request(f, builder);
    }
    /** 真实GET。 */ private MvcResult read(Fixture f, String path) throws Exception { return request(f, get(path)); }
    /** 保留失败首因，成功必须200。 */
    private static JsonNode ok(MvcResult response) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getResponse().getContentAsString());
    }
    /** 同时核对HTTP及错误码。 */
    private static void error(MvcResult response, int status, int code) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(response.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(code);
    }
    /** 新OTA引用必须先清理，再删除本例模型与项目；不修改触发器或禁用外键。 */
    @AfterEach
    void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            owner.update("DELETE FROM ota_trust_bundle WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_trust_domain WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware_creation_request WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM ota_firmware WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.update("DELETE FROM sys_account WHERE id IN (?, ?)", fixture.accountId(), fixture.ownerId());
        }
        fixtures.clear();
    }

    /** 独立身份骨架与已发布类型/不可变模型，owner只准备夹具。 */
    private Fixture seed(ProjectRole role) { return seedFixture(CONFIGURED, role); }
    /** 未配置范围的独立成员图。 */
    private Fixture seedOther(ProjectRole role) {
        return seedFixture(new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate()), role);
    }
    /** 只播种身份与合法类型，域及包必须由HTTP创建。 */
    private Fixture seedFixture(Fixture fixture, ProjectRole role) {
        fixtures.add(fixture);
        JdbcTemplate owner = owner();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA测试租户')", fixture.tenantId());
        for (UUID account : List.of(fixture.accountId(), fixture.ownerId())) {
            owner.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'{noop}unused','OTA测试',now())",
                    account, account + "@example.invalid");
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                    Uuid7.generate(), fixture.tenantId(), account);
        }
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA测试项目','sh-1',?)",
                fixture.projectId(), fixture.tenantId(), "ota_" + fixture.projectId().toString().replace("-", ""));
        UUID ownerId = role == ProjectRole.OWNER ? fixture.accountId() : fixture.ownerId();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.projectId(), ownerId);
        if (role != ProjectRole.OWNER) {
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,?)",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId(), role.name());
        }
        owner.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA测试类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.typeId(), fixture.tenantId(), fixture.projectId(), "type_" + fixture.typeId(), "product_" + fixture.typeId());
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.modelId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), "a".repeat(64));
        return fixture;
    }

    /** owner连接仅用于准备/清理与观察，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 每例全部归属，确保失败后也能精确清理。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID ownerId, UUID typeId, UUID modelId) { }
}
