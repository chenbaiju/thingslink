package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.time.Instant;
import java.util.UUID;

/** 既有提交许可的独立对账采用；调用者已完成真实查询及报告认证，必须共享原事务。 */
public interface OtaDeviceReconciliationPort {
    /** 保留原提交裁决，以新查询身份记录来源CAS或观察，不重新签发不可逆操作。 */
    Result apply(Command command);
    /** 完整冻结元组，查询身份是新的独立模型转换键。 */
    record Command(AuthenticatedDeviceIdentity identity, UUID deviceTypeId,
                   OtaDeviceCommitPort.ModelIdentity expectedSource, OtaDeviceCommitPort.ModelIdentity target,
                   UUID originalPermitId, UUID reconciliationId, long sourceCommittedSecurityVersion,
                   long committedSecurityVersion, String artifactSha256, boolean adoptModel) { }
    /** 原提交和独立对账记录身份不互相替代，重放保留原裁决。 */
    record Result(Decision decision, UUID receiptId, UUID originalCommitReceiptId,
                  UUID bindingTransitionId, Instant recordedAt, boolean replay) { }
    /** 本域已采用结果，不代表硬件证明的获取过程。 */
    enum Decision {
        /** 精确来源CAS完成实际模型转换。 */ CHANGED,
        /** 模型本来相同，仍保存独立对账证据。 */ SAME_MODEL,
        /** 安全事实保存但当前来源不符。 */ SOURCE_CONFLICT,
        /** 仅记录真实已提交观察，不改变模型。 */ OBSERVED_ONLY,
        /** 当前物理身份或凭据无效，零写入。 */ IDENTITY_REJECTED,
        /** 原许可元组或安全下限冲突，零写入。 */ SECURITY_CONFLICT
    }
}
