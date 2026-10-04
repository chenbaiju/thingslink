package com.things.link.ota.application;

import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaPublicationRepository;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.domain.OtaReleaseDownloadErrorCode;
import com.things.link.ota.domain.OtaUploadRepository;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 管理端下载资格短事务，不授予设备活动派发资格且不执行对象网络。 */
@Service
public class OtaReleaseDownloadService {
    /** 真实项目成员。 */ private final ProjectService projects;
    /** 项目持续ACTIVE与代次锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 固件权威身份。 */ private final OtaFirmwareRepository firmwares;
    /** 固定上传版本与对象责任。 */ private final OtaUploadRepository uploads;
    /** 不可变签名发布关系。 */ private final OtaPublicationRepository publications;
    /** 当前可信根与下载key资格。 */ private final OtaTrustService trust;
    /** 原Grant与manifest验签。 */ private final OtaPublicationContract contract;
    /** URL以外的安全下载审计。 */ private final AuditLogService audit;

    /** 构造仅注入领域边界，不执行网络。 */
    public OtaReleaseDownloadService(ProjectService projects,ProjectLifecycleAccessService lifecycle,
            OtaFirmwareRepository firmwares,OtaUploadRepository uploads,OtaPublicationRepository publications,
            OtaTrustService trust,OtaPublicationContract contract,AuditLogService audit) {
        this.projects=projects;this.lifecycle=lifecycle;this.firmwares=firmwares;this.uploads=uploads;
        this.publications=publications;this.trust=trust;this.contract=contract;this.audit=audit;
    }

    /** 每次读取都独立短事务，返回公开密码材料不等于设备资格。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Snapshot read(UUID projectId,UUID firmwareId) { return current(projectId,firmwareId); }

    /** 管理端设备资格查询加入外层事务，所有发布图与信任锁持续到资格判定结束。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot lockForQualification(UUID projectId, UUID firmwareId) {
        return current(projectId, firmwareId);
    }

    /** 网络结束后重新锁定和比较完整快照，失败时调用方不得返回已构造URL。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void confirm(Snapshot expected) {
        if (expected==null) throw ineligible();
        Snapshot actual=current(expected.firmware().projectId(),expected.firmware().id());
        if (!same(expected,actual)) throw ineligible();
        audit.record(new AuditLogEntry(actual.firmware().tenantId(),actual.firmware().projectId(),actual.accountId(),
                "project",actual.firmware().projectId(),"ota.release.download.issued",Map.of(
                "firmwareId",actual.firmware().id().toString(),"publicationId",actual.release().publicationId().toString(),
                "keyFingerprint",actual.grant().binding().fingerprint())));
    }

    /** 固定锁顺序：项目、固件、上传、尝试、当前域；不得依赖历史创建人的权限。 */
    private Snapshot current(UUID project,UUID firmwareId) {
        manage(project);
        UUID tenant=projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant,project);
        manage(project);
        var facts = currentFacts(tenant, project, firmwareId, null);
        return new Snapshot(facts.firmware(), facts.upload(), facts.publication(), facts.release(), facts.grant(),
                TenantContext.require().accountId());
    }

    /** 后台只取发布事实，不借管理账号或REQUIRES_NEW释放外层锁。 */
    ReleaseFacts lockRuntime(OtaRuntimeScope scope, UUID firmwareId) {
        scope.assertActive();
        lifecycle.requireActiveForWrite(scope.tenant(), scope.project());
        return currentFacts(scope.tenant(), scope.project(), firmwareId, scope);
    }

    /** 同一发布图验证；scope仅由包内验真器产生，null仅供已授权管理入口。 */
    private ReleaseFacts currentFacts(UUID tenant, UUID project, UUID firmwareId, OtaRuntimeScope scope) {
        OtaFirmware firmware=firmwares.find(project,firmwareId,true).orElseThrow(OtaReleaseDownloadService::notFound);
        OtaRelease release=publications.findRelease(project,firmwareId).orElseThrow(OtaReleaseDownloadService::notFound);
        OtaUploadSession upload=uploads.find(project,firmwareId,release.uploadSessionId(),true)
                .orElseThrow(OtaReleaseDownloadService::ineligible);
        OtaPublication publication=publications.find(project,firmwareId,release.publicationId(),true)
                .orElseThrow(OtaReleaseDownloadService::ineligible);
        if (!"READY".equals(firmware.status()) || !"ADOPTED".equals(upload.status())
                || !"COMMITTED".equals(publication.status()) || upload.cancelRequestedAt()!=null
                || !tenant.equals(firmware.tenantId()) || !tenant.equals(release.tenantId())
                || !tenant.equals(upload.tenantId()) || !tenant.equals(publication.tenantId())
                || !project.equals(release.projectId()) || !project.equals(upload.projectId())
                || !project.equals(publication.projectId()) || !firmwareId.equals(release.firmwareId())
                || !firmwareId.equals(upload.firmwareId()) || !firmwareId.equals(publication.firmwareId())
                || !release.id().equals(publication.id()) || !release.uploadSessionId().equals(publication.uploadSessionId())
                || !upload.id().equals(publication.uploadSessionId()) || firmware.cancelledAt()!=null
                || firmware.revision()!=publication.firmwareRevision()+2 || upload.revision()!=publication.uploadRevision()+1
                || publication.projectGeneration()!=upload.projectGeneration()
                || !lifecycle.lockActiveForWrite(tenant,project,upload.projectGeneration())
                || upload.versionId()==null || upload.versionId().isBlank() || "null".equals(upload.versionId())
                || upload.writeSettledAt()==null || publication.leaseToken()!=null || publication.leaseUntil()!=null
                || !Arrays.equals(release.canonicalManifest(),publication.canonicalManifest())
                || !Arrays.equals(release.trustSnapshot(),publication.trustSnapshot())
                || !Arrays.equals(release.spki(),publication.spki()) || !Arrays.equals(release.signature(),publication.signature())
                || !Objects.equals(release.receipt(),publication.receipt())) throw ineligible();
        try {
            var original=contract.restore(release.trustSnapshot());
            contract.verifySignature(release.canonicalManifest(),original,release.spki(),release.signature(),release.receipt());
            validateFields(firmware,upload,release,original);
            var actual = scope == null
                    ? trust.lockForDownload(project, firmware.deviceTypeId(), original.binding().trustDomain(),
                            original.binding().keyVersion(), original.binding().profile(), original.binding().fingerprint())
                    : trust.lockRuntime(scope, firmware.deviceTypeId(), original.binding().trustDomain(),
                            original.binding().keyVersion(), original.binding().profile(), original.binding().fingerprint());
            // 当前bundle/policy修订可以前进，但不能换根、换SPKI或减小已发布最低bundle。
            if (!original.tenantId().equals(actual.tenantId()) || !original.projectId().equals(actual.projectId())
                    || !original.deviceTypeId().equals(actual.deviceTypeId())
                    || !original.rootFingerprint().equals(actual.rootFingerprint())
                    || !Arrays.equals(original.spki(),actual.spki()) || actual.bundleVersion()<original.bundleVersion()
                    || !original.binding().trustDomain().equals(actual.binding().trustDomain())
                    || !original.binding().keyVersion().equals(actual.binding().keyVersion())
                    || original.binding().profile()!=actual.binding().profile()
                    || !original.binding().fingerprint().equals(actual.binding().fingerprint())) throw ineligible();
            return new ReleaseFacts(firmware, upload, publication, release, actual);
        } catch (IllegalArgumentException failure) { throw ineligible(); }
    }

    /** 对原签名声明与最终持久父事实做精确比较，不伪造DRAFT状态调用发布校验。 */
    private static void validateFields(OtaFirmware f,OtaUploadSession u,OtaRelease r,OtaTrustService.Grant original) {
        Map<String,Object> fields=new OtaCanonicalJson().parseObject(r.canonicalManifest());
        if (!f.tenantId().equals(original.tenantId()) || !f.projectId().equals(original.projectId())
                || !f.deviceTypeId().equals(original.deviceTypeId())) throw ineligible();
        equal(fields.get("firmwareId"),f.id().toString());
        equal(fields.get("firmwareVersion"),f.firmwareVersion());
        equal(fields.get("deviceTypeId"),f.deviceTypeId().toString());
        equal(fields.get("productKey"),f.productKey());
        equal(fields.get("thingModelVersionId"),f.thingModelVersionId().toString());
        equal(fields.get("thingModelSchemaDigestAlgorithm"),f.schemaDigestAlgorithm());
        equal(fields.get("thingModelSchemaDigest"),f.schemaDigest());
        equal(OtaTrustBundleCodec.object(fields.get("requirements")).get("profile"),f.schemaProfile());
        equal(fields.get("artifactSize"),u.expectedLength());
        equal(fields.get("artifactSha256"),u.expectedSha256());
    }

    /** 数组必须按字节比较；revision相等不能替代完整事实快照比较。 */
    private boolean same(Snapshot a,Snapshot b) {
        return Objects.equals(a.accountId(),b.accountId()) && a.firmware().equals(b.firmware()) && a.upload().equals(b.upload())
                && sameRelease(a.release(),b.release()) && samePublication(a.publication(),b.publication())
                && contract.same(a.grant(),b.grant());
    }
    /** 不可变release每个字段均参与最终复核。 */
    private static boolean sameRelease(OtaRelease a,OtaRelease b) {
        return Objects.equals(a.id(),b.id()) && Objects.equals(a.tenantId(),b.tenantId()) && Objects.equals(a.projectId(),b.projectId())
                && Objects.equals(a.firmwareId(),b.firmwareId()) && Objects.equals(a.uploadSessionId(),b.uploadSessionId())
                && Objects.equals(a.publicationId(),b.publicationId()) && Arrays.equals(a.canonicalManifest(),b.canonicalManifest())
                && Arrays.equals(a.trustSnapshot(),b.trustSnapshot()) && Arrays.equals(a.spki(),b.spki())
                && Arrays.equals(a.signature(),b.signature()) && Objects.equals(a.receipt(),b.receipt())
                && Objects.equals(a.createdAt(),b.createdAt());
    }
    /** COMMITTED尝试的完整字段比较，不依赖record的数组引用相等。 */
    private static boolean samePublication(OtaPublication a,OtaPublication b) {
        return Objects.equals(a.id(),b.id()) && Objects.equals(a.tenantId(),b.tenantId()) && Objects.equals(a.projectId(),b.projectId())
                && Objects.equals(a.firmwareId(),b.firmwareId()) && Objects.equals(a.uploadSessionId(),b.uploadSessionId())
                && Objects.equals(a.createdBy(),b.createdBy()) && Objects.equals(a.requestId(),b.requestId())
                && a.projectGeneration()==b.projectGeneration() && a.firmwareRevision()==b.firmwareRevision()
                && a.uploadRevision()==b.uploadRevision() && Arrays.equals(a.canonicalManifest(),b.canonicalManifest())
                && Arrays.equals(a.trustSnapshot(),b.trustSnapshot()) && Objects.equals(a.status(),b.status()) && a.revision()==b.revision()
                && Arrays.equals(a.spki(),b.spki()) && Arrays.equals(a.signature(),b.signature())
                && Objects.equals(a.receipt(),b.receipt()) && Objects.equals(a.failureCode(),b.failureCode())
                && Objects.equals(a.leaseToken(),b.leaseToken()) && Objects.equals(a.leaseUntil(),b.leaseUntil())
                && Objects.equals(a.createdAt(),b.createdAt()) && Objects.equals(a.updatedAt(),b.updatedAt());
    }
    /** 当前OWNER/ADMIN之外不开放下载。 */
    private void manage(UUID project) {
        ProjectRole role=projects.requireRoleInProject(project);
        if(role!=ProjectRole.OWNER && role!=ProjectRole.ADMIN) throw new BusinessException(OtaReleaseDownloadErrorCode.FORBIDDEN);
    }
    /** 不接受缺值或数字类型隐式转换。 */
    private static void equal(Object actual,Object expected) { if(!Objects.equals(actual,expected)) throw ineligible(); }
    /** 固定404不透露其他项目事实。 */
    private static BusinessException notFound() { return new BusinessException(OtaReleaseDownloadErrorCode.NOT_FOUND); }
    /** 资格或签名关系不完整不可生成地址。 */
    private static BusinessException ineligible() { return new BusinessException(OtaReleaseDownloadErrorCode.INELIGIBLE); }
    /** 无账号的纯发布事实，不能作为网络下载授权。
     * @param firmware 当前固件
     * @param upload 当前对象责任
     * @param publication 原发布尝试
     * @param release 固定签名发布
     * @param grant 当前信任资格
     */
    record ReleaseFacts(OtaFirmware firmware, OtaUploadSession upload, OtaPublication publication,
                        OtaRelease release, OtaTrustService.Grant grant) { }
    /** 内部完整快照，API必须另白名单投影。
     * @param firmware READY固件
     * @param upload 精确ADOPTED对象
     * @param publication COMMITTED尝试
     * @param release 不可变签名发布物
     * @param grant 本次当前下载资格
     * @param accountId 本次真实操作者
     */
    public record Snapshot(OtaFirmware firmware,OtaUploadSession upload,OtaPublication publication,OtaRelease release,
                           OtaTrustService.Grant grant,UUID accountId) { }
}
