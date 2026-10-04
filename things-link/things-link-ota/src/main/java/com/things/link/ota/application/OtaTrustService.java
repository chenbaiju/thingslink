package com.things.link.ota.application;

import com.things.link.ota.domain.OtaTrustErrorCode;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaTrustRepository;
import com.things.link.ota.domain.OtaTrustState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 根签名配置的原子导入及发布资格；不存在signer或READY成功替身。 */
@Service
public class OtaTrustService {
    /** 精确域事实与不可变历史包。 */ private final OtaTrustRepository repository;
    /** 受控启动配置，不能由HTTP选择信任根。 */ private final OtaTrustAnchors anchors;
    /** 真实项目与成员角色。 */ private final ProjectService projects;
    /** 发布和导入都需持续ACTIVE许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 登记和更新与审计同事务。 */ private final AuditLogService audit;
    /** 信任撤销与准入共用项目控制锁及安全暂停事务。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 纯签名/闭集字段验真，不执行远端调用。 */ private final OtaTrustBundleCodec codec = new OtaTrustBundleCodec();

    /** 注入真实权限、事务仓储和受控根，不接收私钥。 */
    public OtaTrustService(OtaTrustRepository repository, OtaTrustAnchors anchors, ProjectService projects,
            ProjectLifecycleAccessService lifecycle, AuditLogService audit, OtaCampaignRuntimeRepository runtime) {
        this.repository = repository; this.anchors = anchors; this.projects = projects;
        this.lifecycle = lifecycle; this.audit = audit; this.runtime = runtime;
    }

    /** 根验真及全部密钥转移在一次短事务提交，公共幂等墓碑负责相同请求重放。 */
    @Transactional
    public OtaTrustState importBundle(UUID projectId, String domain, String key, String expectedRevision,
            byte[] bundle, byte[] signature) {
        UUID tenant = requireWrite(projectId);
        requireDomain(domain);
        requireKey(key);
        long expected = revision(expectedRevision);
        var root = root(tenant, projectId, domain);
        if (signature == null || signature.length != 64) throw invalid();
        byte[] signatureSnapshot = signature.clone();
        var incoming = verify(bundle, signatureSnapshot, root);
        if (!domain.equals(incoming.trustDomain())) throw invalid();
        runtime.controlLock(tenant, projectId);
        repository.lockImport(tenant, projectId, domain);
        OtaTrustState previous = repository.find(projectId, domain, true, false).orElse(null);
        if (previous == null && expected != 0 || previous != null && previous.revision() != expected) throw conflict();
        if (previous != null) {
            if (!previous.tenantId().equals(tenant) || !sameRoot(previous, root)
                    || root.policyRevision() < previous.policyRevision()
                    || root.policyRevision() == previous.policyRevision() && !root.policyHash().equals(previous.policyHash())
                    || incoming.bundleVersion() <= previous.bundleVersion() || previous.revision() == Long.MAX_VALUE) {
                throw conflict();
            }
            var priorBundle = verifiedStored(previous, root);
            requireTransitions(priorBundle, incoming);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant created = previous == null ? now : previous.createdAt();
        if (now.isBefore(created)) now = created;
        OtaTrustState next = new OtaTrustState(tenant, projectId, domain, root.rootProfile().value(),
                root.rootFingerprint(), root.policyRevision(), root.policyHash(), expected + 1,
                incoming.bundleVersion(), incoming.sha256(), incoming.canonical(), signatureSnapshot, created, now);
        try {
            if (previous == null) repository.create(next);
            else if (!repository.replace(expected, next)) throw conflict();
        } catch (DataIntegrityViolationException failure) { throw conflict(); }
        for (var revoked : incoming.keys()) {
            if ("REVOKED".equals(revoked.state())) {
                for (UUID firmware : runtime.firmwaresUsingKey(projectId, domain, revoked.keyVersion())) {
                    runtime.securityPause(projectId, firmware, TenantContext.require().accountId(), "SIGNING_KEY_REVOKED");
                }
            }
        }
        // 审计关联域摘要和版本，不复制签名正文或受控根配置。
        audit.record(new AuditLogEntry(tenant, projectId, TenantContext.require().accountId(), "project",
                projectId, "ota.trust.bundle.imported", Map.of("trustDomain", domain,
                "bundleVersion", Long.toString(next.bundleVersion()), "revision", Long.toString(next.revision()),
                "rootFingerprint", next.rootFingerprint(), "bundleSha256", next.bundleSha256())));
        return next;
    }

    /** 登记状态只作展示，不能从该返回值推定密钥当前可发布或设备已经确认。 */
    @Transactional(readOnly = true)
    public OtaTrustState find(UUID projectId, String domain) {
        projects.requireRoleInProject(projectId);
        requireDomain(domain);
        return required(projectId, domain, false);
    }

    /**
     * 当前成员分页读取本项目全部信任域摘要，按域名升序；无域返回空页而不是404。
     *
     * <p>与{@link #find}同一授权口径：{@code ota:read}由项目成员资格判定，不解锁任何
     * 管理或发布资格。每个域额外投影<b>当前包内</b>ACTIVE发布键的版本与指纹摘要，
     * 供控制台渲染信任/密钥表；摘要从已登记、导入时已根验真且不可更新的规范字节解析，
     * 本读取不重新验根、不写任何状态，也不提供任何密钥状态变更入口。
     *
     * @param projectId 精确项目
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 按域名升序的域投影游标页
     */
    @Transactional(readOnly = true, timeout = 5)
    public CursorPage<DomainProjection> domains(UUID projectId, String cursor, int limit) {
        projects.requireRoleInProject(projectId);
        return repository.domains(projectId, cursor, limit)
                .map(state -> new DomainProjection(state, activeKey(state)));
    }

    /**
     * 当前成员分页读取指定信任域<b>当前包</b>的逐键公开元数据，按{@code keyVersion}升序。
     *
     * <p>只读且成员可读，与固件/发布历史集合同一{@code ota:read}口径：域不存在、跨项目或
     * RLS不可见统一是{@link OtaTrustErrorCode#NOT_FOUND}的404/70013，与相邻的域摘要读取一致。
     * 返回类型是{@link OtaTrustBundleCodec.PublicKeyMetadata}白名单，不含SPKI、规范字节、
     * 签名或任何可改变键状态的字段。
     *
     * <p>键不是独立数据行，而是冻结在{@code ota_trust_bundle.canonical_bundle}里的闭集数组
     * （每个包最多64键），因此分页在应用层完成：先按{@code keyVersion}排序，再用不透明游标
     * 定位。游标载荷绑定{@code project|domain|bundleVersion|keyVersion}：跨项目、跨域、
     * 跨包版本或指向本包不存在的键一律判为非法参数，避免在轮换后混合两代键。
     *
     * @param projectId 精确项目
     * @param domain 精确信任域
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 按版本升序的键公开元数据游标页
     */
    @Transactional(readOnly = true, timeout = 5)
    public CursorPage<OtaTrustBundleCodec.PublicKeyMetadata> keys(UUID projectId, String domain,
            String cursor, int limit) {
        projects.requireRoleInProject(projectId);
        requireDomain(domain);
        if (limit < 1 || limit > 100) throw parameter();
        OtaTrustState state = required(projectId, domain, false);
        var inventory = inspectStored(state);
        List<OtaTrustBundleCodec.PublicKeyMetadata> ordered = inventory.keys().stream()
                .sorted(Comparator.comparing(OtaTrustBundleCodec.PublicKeyMetadata::keyVersion)).toList();
        int start = 0;
        if (cursor != null && !cursor.isEmpty()) {
            String after = decodeKeyCursor(projectId, domain, state.bundleVersion(), cursor);
            int found = -1;
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).keyVersion().equals(after)) { found = index; break; }
            }
            if (found < 0) throw invalidKeyCursor();
            start = found + 1;
        }
        if (start >= ordered.size()) return CursorPage.last(List.of());
        List<OtaTrustBundleCodec.PublicKeyMetadata> remaining = ordered.subList(start, ordered.size());
        if (remaining.size() <= limit) return CursorPage.last(List.copyOf(remaining));
        List<OtaTrustBundleCodec.PublicKeyMetadata> items = List.copyOf(remaining.subList(0, limit));
        return CursorPage.of(items, Cursor.encode(projectId + "|" + domain + "|" + state.bundleVersion()
                + "|" + items.getLast().keyVersion()));
    }

    /** 解码并绑定键游标的四项范围；任何不匹配都不回显解码内容。 */
    private static String decodeKeyCursor(UUID projectId, String domain, long bundleVersion, String cursor) {
        try {
            if (cursor.length() > 512) throw invalidKeyCursor();
            String[] parts = Cursor.decode(cursor).split("\\|", -1);
            if (parts.length != 4 || !projectId.toString().equals(parts[0]) || !domain.equals(parts[1])
                    || Long.parseLong(parts[2]) != bundleVersion
                    || !parts[3].matches("[A-Za-z0-9._:/-]{1,256}")) throw invalidKeyCursor();
            return parts[3];
        } catch (RuntimeException failure) { throw invalidKeyCursor(); }
    }

    /** 当前包的逐键公开清单，并核对其与持久指针一致；损坏显式失败而不是当作空域。 */
    private OtaTrustBundleCodec.PublicInventory inspectStored(OtaTrustState state) {
        OtaTrustBundleCodec.PublicInventory inventory;
        try { inventory = codec.inspect(state.canonicalBundle()); }
        catch (IllegalArgumentException failure) {
            throw new DataIntegrityViolationException("OTA信任域当前包不可解析", failure);
        }
        if (!inventory.trustDomain().equals(state.trustDomain())
                || inventory.bundleVersion() != state.bundleVersion()
                || !inventory.bundleSha256().equals(state.bundleSha256()))
            throw new DataIntegrityViolationException("OTA信任域当前包与指针不一致");
        return inventory;
    }

    /** 每个包根验真时已要求恰好一个ACTIVE键；缺失说明持久事实损坏。 */
    private OtaTrustBundleCodec.PublicKeyMetadata activeKey(OtaTrustState state) {
        return inspectStored(state).keys().stream().filter(key -> "ACTIVE".equals(key.state())).findFirst()
                .orElseThrow(() -> new DataIntegrityViolationException("OTA信任域当前包缺少ACTIVE发布键"));
    }

    /** 键游标或范围不合法。 */
    private static BusinessException invalidKeyCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "信任密钥分页参数不合法");
    }

    /**
     * 成员可读的信任域投影：持久域事实加上从当前包解析出的ACTIVE发布键公开摘要。
     *
     * <p>仅作为API层白名单来源；{@code state}里的规范字节与签名不进入任何响应，
     * {@code activeKey}不含SPKI。API层据此显式挑选字段，而不是把整个领域对象序列化出去。
     *
     * @param state 当前域及精确历史包
     * @param activeKey 当前包内ACTIVE发布键的公开元数据
     */
    public record DomainProjection(OtaTrustState state, OtaTrustBundleCodec.PublicKeyMetadata activeKey) { }

    /** 签名前后读取可信资格，最终采用仍必须调用持锁版本。 */
    @Transactional
    public Grant activeKey(UUID projectId, UUID deviceTypeId, String domain) {
        return grant(projectId, deviceTypeId, domain, false);
    }

    /** 与READY调用者共用事务并持域共享锁；禁止在无事务时返回假原子快照。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Grant lockForPublication(UUID projectId, UUID deviceTypeId, String domain) {
        return grant(projectId, deviceTypeId, domain, true);
    }

    /** 当前管理下载仍需可信根和域共享锁；精确旧键可VERIFY_ONLY，不选择其他替代键。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Grant lockForDownload(UUID projectId, UUID deviceTypeId, String trustDomain, String keyVersion,
                                 OtaSignatureProfile profile, String fingerprint) {
        UUID tenant = requireWrite(projectId);
        return downloadFacts(tenant, projectId, deviceTypeId, trustDomain, keyVersion, profile, fingerprint);
    }

    /** 域内真实租约事务资格，不开放管理权限绕过入口。 */
    Grant lockRuntime(OtaRuntimeScope scope, UUID type, String domain, String keyVersion,
                      OtaSignatureProfile profile, String fingerprint) {
        scope.assertActive();
        lifecycle.requireActiveForWrite(scope.tenant(), scope.project());
        return downloadFacts(scope.tenant(), scope.project(), type, domain, keyVersion, profile, fingerprint);
    }

    /** 公共管理与受控后台共享精确根、包及发布键资格。 */
    private Grant downloadFacts(UUID tenant, UUID projectId, UUID deviceTypeId, String trustDomain,
                                String keyVersion, OtaSignatureProfile profile, String fingerprint) {
        requireDomain(trustDomain);
        if (keyVersion == null || !keyVersion.matches("[A-Za-z0-9._:/-]{1,256}") || profile == null
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw ineligible();
        var root = root(tenant, projectId, trustDomain);
        if (deviceTypeId == null || !root.allowedDeviceTypeIds().contains(deviceTypeId)) throw ineligible();
        OtaTrustState state = required(projectId, trustDomain, true);
        if (!tenant.equals(state.tenantId()) || !sameRoot(state, root)
                || root.policyRevision() != state.policyRevision() || !root.policyHash().equals(state.policyHash())) {
            throw new BusinessException(OtaTrustErrorCode.UNAVAILABLE);
        }
        var bundle = verifiedStored(state, root);
        var key = bundle.keys().stream().filter(item -> keyVersion.equals(item.keyVersion())
                && profile == item.profile() && fingerprint.equals(item.fingerprint()))
                .findFirst().orElseThrow(OtaTrustService::ineligible);
        long now = Instant.now().getEpochSecond();
        if (!("ACTIVE".equals(key.state()) || "VERIFY_ONLY".equals(key.state()))
                || now < key.notBefore() || now >= key.notAfter()) throw ineligible();
        var binding = new OtaSigningTrustSource.Binding(trustDomain, key.keyVersion(), key.profile(),
                key.fingerprint(), state.revision());
        return new Grant(tenant, projectId, deviceTypeId, state.bundleVersion(), state.policyRevision(),
                state.policyHash(), state.rootFingerprint(), binding, key.spki());
    }

    /** 身份、当前配置、根签名和时间均重新校验，不能选用备用键静默降级。 */
    private Grant grant(UUID projectId, UUID deviceTypeId, String domain, boolean sharedLock) {
        UUID tenant = requireWrite(projectId);
        requireDomain(domain);
        var root = root(tenant, projectId, domain);
        if (deviceTypeId == null || !root.allowedDeviceTypeIds().contains(deviceTypeId)) throw ineligible();
        OtaTrustState state = required(projectId, domain, sharedLock);
        if (!tenant.equals(state.tenantId()) || !sameRoot(state, root)
                || root.policyRevision() != state.policyRevision() || !root.policyHash().equals(state.policyHash())) {
            throw new BusinessException(OtaTrustErrorCode.UNAVAILABLE);
        }
        var bundle = verifiedStored(state, root);
        var key = bundle.keys().stream().filter(item -> "ACTIVE".equals(item.state())).findFirst().orElseThrow(OtaTrustService::invalid);
        long now = Instant.now().getEpochSecond();
        if (now < key.notBefore() || now >= key.notAfter()) throw ineligible();
        var binding = new OtaSigningTrustSource.Binding(domain, key.keyVersion(), key.profile(), key.fingerprint(), state.revision());
        return new Grant(tenant, projectId, deviceTypeId, state.bundleVersion(), state.policyRevision(),
                state.policyHash(), state.rootFingerprint(), binding, key.spki());
    }

    /** 不把数据库当前指针或摘要当根信任来源，始终重验精确不可变包。 */
    private OtaTrustBundleCodec.Bundle verifiedStored(OtaTrustState state, OtaTrustAnchors.Anchor root) {
        var value = verify(state.canonicalBundle(), state.signature(), root);
        if (!value.trustDomain().equals(state.trustDomain()) || value.bundleVersion() != state.bundleVersion()
                || !value.sha256().equals(state.bundleSha256()) || !Arrays.equals(value.canonical(), state.canonicalBundle())) {
            throw invalid();
        }
        return value;
    }

    /** 结构或根验真失败只回固定代码。 */
    private OtaTrustBundleCodec.Bundle verify(byte[] bundle, byte[] signature, OtaTrustAnchors.Anchor root) {
        try { return codec.verify(bundle, signature, root); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** 启动根未配置或不匹配时不能按请求里的SPKI继续。 */
    private OtaTrustAnchors.Anchor root(UUID tenant, UUID project, String domain) {
        try { return anchors.require(tenant, project, domain); }
        catch (IllegalArgumentException failure) { throw new BusinessException(OtaTrustErrorCode.UNAVAILABLE); }
    }

    /** 根Profile与完整指纹不支持V1在线轮换；配置授权变化另有单调revision。 */
    private static boolean sameRoot(OtaTrustState state, OtaTrustAnchors.Anchor root) {
        return state.rootProfile().equals(root.rootProfile().value()) && state.rootFingerprint().equals(root.rootFingerprint());
    }

    /** 历史键不移除、不改身份或有效期，已撤销键不能以更高bundle复活。 */
    private static void requireTransitions(OtaTrustBundleCodec.Bundle previous, OtaTrustBundleCodec.Bundle next) {
        Map<String, OtaTrustBundleCodec.Key> keys = new HashMap<>();
        next.keys().forEach(key -> keys.put(key.keyVersion(), key));
        for (var old : previous.keys()) {
            var current = keys.get(old.keyVersion());
            if (current == null || old.profile() != current.profile() || !old.fingerprint().equals(current.fingerprint())
                    || !Arrays.equals(old.spki(), current.spki()) || old.notBefore() != current.notBefore()
                    || old.notAfter() != current.notAfter() || !allowedState(old.state(), current.state())) throw conflict();
        }
    }

    /** 固定状态图；不接受ACTIVE退回PREPARED或REVOKED恢复。 */
    private static boolean allowedState(String previous, String next) {
        if (previous.equals(next)) return true;
        return switch (previous) {
            case "PREPARED" -> "ACTIVE".equals(next) || "REVOKED".equals(next);
            case "ACTIVE" -> "VERIFY_ONLY".equals(next) || "REVOKED".equals(next);
            case "VERIFY_ONLY" -> "REVOKED".equals(next);
            default -> false;
        };
    }

    /** 精确项目查询，持锁资格不能通过普通读取代替。 */
    private OtaTrustState required(UUID project, String domain, boolean sharedLock) {
        return repository.find(project, domain, false, sharedLock)
                .orElseThrow(() -> new BusinessException(OtaTrustErrorCode.NOT_FOUND));
    }

    /** 与既有固件写相同的项目许可顺序。 */
    private UUID requireWrite(UUID project) {
        requireManage(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireManage(project);
        return tenant;
    }

    /** 仅管理角色能更新签名信任或取得发布资格。 */
    private void requireManage(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(OtaTrustErrorCode.FORBIDDEN);
    }
    /** 域不能包含路径或任意供应商地址。 */
    private static void requireDomain(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw parameter();
    }
    /** 即使内部直接调用也不能省略或接收畸形幂等键。 */
    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw parameter();
    }
    /** 修订不允许前导零、隐式数值转换或溢出。 */
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw parameter();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw parameter(); }
    }
    /** 安全签名配置错误。 */ private static BusinessException invalid() { return new BusinessException(OtaTrustErrorCode.INVALID); }
    /** 安全状态冲突。 */ private static BusinessException conflict() { return new BusinessException(OtaTrustErrorCode.CONFLICT); }
    /** 当前密钥资格失败。 */ private static BusinessException ineligible() { return new BusinessException(OtaTrustErrorCode.INELIGIBLE); }
    /** 参数错误。 */ private static BusinessException parameter() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }

    /**
     * 当前受控资格，只在所声明项目/类型内使用；不证明signer可用或设备已收到bundle。
     * @param tenantId 真实项目租户
     * @param projectId 真实项目
     * @param deviceTypeId 配置授权类型
     * @param bundleVersion 精确根签名包版本
     * @param policyRevision 受控配置修订
     * @param policyHash 精确配置摘要
     * @param rootFingerprint 离线根公开指纹
     * @param binding 协调器可读取的当前发布键
     * @param spki 发布键严格公钥副本
     */
    public record Grant(UUID tenantId, UUID projectId, UUID deviceTypeId, long bundleVersion, long policyRevision,
                        String policyHash, String rootFingerprint, OtaSigningTrustSource.Binding binding, byte[] spki) {
        /** 公钥数组不允许调用方修改可信快照。 */
        public Grant { spki = spki.clone(); }
        /** 每次读取返回新副本。 */
        @Override public byte[] spki() { return spki.clone(); }
    }
}
