package com.things.link.assistant.application;

import com.things.link.assistant.domain.ModelCredentialCipher;
import com.things.link.assistant.domain.ProbeLedger.Status;
import com.things.link.shared.error.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 编排持久认领、短事务准入、单次凭据交付和终态登记；网络调用在事务外执行。 */
@Service
public class ProbeRunService {
    /**
     * 返回当前调用者可见的探针回执，不携带凭据或模型正文。
     * @param attemptId 持久探针机会标识
     * @param sampleIndex 固定样本序号
     * @param status 台账记录的当前终态
     * @param category 固定结果分类码，传输结果不确定时使用未知分类
     * @param usage 已验证的用量证据；没有确定结果时为空
     */
    @io.swagger.v3.oas.annotations.media.Schema(name="AssistantProbeRunView",additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record View(UUID attemptId,int sampleIndex,Status status,String category,ProbeResult.Usage usage) {}
    private final ProbeLedgerService ledger;
    private final ProbeExecutionService execution;
    private final ProbeAuthorizationProvider grants;
    private final ProbeTransport transport;
    private final ModelCredentialCipher cipher;
    public ProbeRunService(ProbeLedgerService l,ProbeExecutionService e,ProbeAuthorizationProvider g,ProbeTransport t,
            ModelCredentialCipher c){
        ledger=l;execution=e;grants=g;transport=t;cipher=c;
    }
    /**
     * 运行一次已授权的固定样本；认领后即消耗机会，错误不会触发自动重试。
     * @param project 必须等于当前身份所选项目的标识
     * @param sample 固定样本序号，取值为 1 至 3
     * @return 个人可见的持久终态及经过校验的用量证据
     * @throws BusinessException 项目或角色不符、授权失效、传输未启用或机会已占用
     */
    @Transactional(propagation=Propagation.NOT_SUPPORTED) public View run(UUID project,int sample){
        if(!java.util.Objects.equals(project,com.things.link.shared.tenant.TenantContext.require().projectId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        var grant=grants.current().filter(a->a.projectId().equals(project))
            .orElseThrow(()->new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT));
        if(!transport.ready()) throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
        var attempt=ledger.claim(project,grant.id(),sample);
        ProbeExecutionService.Permit permit=null;
        ProbeResult result=null;
        boolean settled=false;
        try {
            permit=execution.dispatch(project,attempt,grant);
            var dispatch=permit;
            result=cipher.deliver(permit.credential(),key->transport.execute(dispatch.attempt(),dispatch.authorization(),key));
            settled=true; // 通过身份认证的 Python 响应确认本次调用的连接已关闭
        } catch (BusinessException|IllegalStateException ignored) {
            // 公开异常不暴露原始加密错误、HTTP 错误或凭据；已认领的机会仍计为消耗。
        }
        Status target=result==null?Status.UNKNOWN:Status.valueOf(result.outcome());
        try {
            var done=ledger.finish(project,attempt.id(),target);
            return new View(done.id(),sample,done.status(),result==null?"TRANSPORT_UNKNOWN":result.category(),result==null?null:result.usage());
        } catch(BusinessException expired){
            var done=ledger.read(project,attempt.id());
            return new View(done.id(),sample,done.status(),"TRANSPORT_UNKNOWN",null);
        } finally {
            if(settled&&permit!=null) {
                try{execution.release(project,permit);}catch(BusinessException ignored){/* 不续期，等待占用到期 */}
            }
        }
    }
}
