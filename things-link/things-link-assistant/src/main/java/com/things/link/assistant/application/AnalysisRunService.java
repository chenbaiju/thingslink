package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.ModelCredentialCipher;
import com.things.link.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 受权固定同步流程；解密和传输在事务外，结果必须凭单次能力最终确权，固定裁决待审时不能签发。 */
@Service
public class AnalysisRunService {
    /** 固定状态类别，未知消费不能改为零用量，未准入不能改为分析成功。 */
    public enum Category { UNAVAILABLE, REPLAY, TRANSPORT_UNKNOWN, UNQUALIFIED, SUCCEEDED }
    /** 首次结果可含瞬时正文；未准入、未知和重放一律为空，不保存正文或密钥。 */
    @io.swagger.v3.oas.annotations.media.Schema(name="AssistantAnalysisRunView",requiredProperties={"call","category","result"},
            additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record View(AnalysisCallView call, Category category, AnalysisResultView result) {
        /** 类别、调用与结果必须一致；成功元数据重放仍不能携带正文。 */
        public View {
            if (category == null || ((category == Category.UNAVAILABLE) != (call == null))
                    || ((category == Category.SUCCEEDED) != (result != null))
                    || (result != null && call.status() != AnalysisCall.Status.SUCCEEDED))
                throw new IllegalArgumentException("INVALID_ANALYSIS_VIEW");
        }
        /** 构造不带正文的关闭、未知、未准入或重放结果。 */
        public View(AnalysisCallView call, Category category) { this(call,category,null); }
        @Override public String toString() { return "分析调用结果[" + category + ",内容已隐藏]"; }
    }
    /** 业务状态与内部装配分离；受审可发起不等于供应商在线或余额充足。 */
    public enum UnavailableReason { INTERNAL_TRANSPORT_DISABLED, MODEL_ADMISSION_PENDING,
        PROJECT_MODEL_CONFIGURATION_DISABLED, REVIEWED_CONFIGURATION_AVAILABLE }
    /** 仅返回固定原因，不泄露服务地址、证书、项目凭据或配置版本。 */
    @io.swagger.v3.oas.annotations.media.Schema(name="AssistantAnalysisAvailability",requiredProperties={"businessAvailable","reason"},
            additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Availability(boolean businessAvailable, UnavailableReason reason) {
        public Availability {
            if (reason == null || businessAvailable != (reason == UnavailableReason.REVIEWED_CONFIGURATION_AVAILABLE))
                throw new IllegalArgumentException("INVALID_ANALYSIS_AVAILABILITY");
        }
    }
    private final AnalysisCallService calls;
    private final DeviceEvidenceService evidence;
    private final OutboundEvidenceProjector projector;
    private final ModelCredentialCipher cipher;
    private final AnalysisTransport transport;

    public AnalysisRunService(AnalysisCallService calls, DeviceEvidenceService evidence,
            OutboundEvidenceProjector projector, ModelCredentialCipher cipher, AnalysisTransport transport) {
        this.calls = calls; this.evidence = evidence; this.projector = projector;
        this.cipher = cipher; this.transport = transport;
    }

    /**
     * 同一键只允许首次流程继续；关闭通道不预留元数据，也不读取项目密钥。
     * @param project 当前身份所选项目，必须具备付费分析角色
     * @param key 规范且未过期的调用幂等键，不是模型密钥
     * @param request 封闭模板及设备、模型版本和有界属性键
     * @return 当前个人元数据与分类；仅受信凭证首次最终确权后可带正文，生产签发链仍关闭
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public View run(UUID project, String key, AnalysisRequest request) {
        calls.authorizeForAnalysis(project);
        calls.validateRequestIdentity(key, request);
        if (!transport.ready()) return new View(null, Category.UNAVAILABLE);
        var reservation = calls.prepare(project, key, request);
        if (!reservation.fresh()) return new View(reservation.call(), Category.REPLAY);
        UUID id = reservation.call().id();
        AnalysisDispatchPermit permit = null;
        boolean settled = false;
        try {
            var snapshot = evidence.read(project, request.deviceId(), request.expectedModelVersionId(), request.propertyKeys());
            var prepared = projector.prepare(snapshot, request.template());
            permit = calls.dispatch(project, id, request);
            var execution = calls.claimForExecution(project, permit);
            calls.verifyExecution(project, execution);
            var receipt = cipher.deliver(execution.credential(), secret ->
                    transport.execute(execution.call(), prepared.input(), secret));
            if (receipt instanceof AnalysisTransport.Receipt.Releasable releasable) {
                settled = true;
                var released = calls.releaseResult(project,execution,releasable.permit());
                return new View(released.call(),Category.SUCCEEDED,AnalysisResultView.from(released));
            }
            if (receipt != AnalysisTransport.Receipt.UNQUALIFIED) throw new IllegalStateException("INVALID_ANALYSIS_RECEIPT");
            settled = true;
            return new View(calls.completeUnqualified(project, execution), Category.UNQUALIFIED);
        } catch (BusinessException denied) {
            markUnknown(project, id);
            throw denied;
        } catch (RuntimeException ignored) {
            // 不回显加密、模型或网络异常；再次确权后只返回未知元数据。
            return new View(calls.complete(project, id, AnalysisCall.Status.UNKNOWN), Category.TRANSPORT_UNKNOWN);
        } finally {
            if (settled && permit != null) {
                try { calls.releasePermit(project, permit); }
                catch (RuntimeException ignored) { /* 无法确权或持久释放时等待原期限，不复用许可。 */ }
            }
        }
    }

    /**
     * 读取当前项目付费角色可见的受审状态；只读配置元数据，不解密或发起网络。
     * @param project 当前身份所选项目
     * @return 固定批准与项目启用共同通过才可发起；提交仍重验，不承诺供应商在线或余额
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Availability availability(UUID project) {
        calls.authorizeForAnalysis(project);
        if (!transport.ready()) return new Availability(false, UnavailableReason.INTERNAL_TRANSPORT_DISABLED);
        var approval = transport.review();
        if (approval.isEmpty() || !approval.get().isCurrent())
            return new Availability(false, UnavailableReason.MODEL_ADMISSION_PENDING);
        if (!calls.analysisConfigurationAvailable(project))
            return new Availability(false, UnavailableReason.PROJECT_MODEL_CONFIGURATION_DISABLED);
        calls.authorizeForAnalysis(project);
        if (!approval.get().isCurrent()) return new Availability(false, UnavailableReason.MODEL_ADMISSION_PENDING);
        return new Availability(true, UnavailableReason.REVIEWED_CONFIGURATION_AVAILABLE);
    }

    /** 失败时尽力登记未知；撤权或来源消失后不绕过原权限更新元数据。 */
    private void markUnknown(UUID project, UUID id) {
        try { calls.complete(project, id, AnalysisCall.Status.UNKNOWN); }
        catch (RuntimeException ignored) { /* 后续受权读取按原期限收敛，不记录私有异常。 */ }
    }
}
