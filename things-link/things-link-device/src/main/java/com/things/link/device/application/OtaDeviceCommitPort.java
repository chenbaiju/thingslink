package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 已验证设备提交证据的本域采用端口；调用者保有项目锁、真实认证与同一事务。 */
public interface OtaDeviceCommitPort {
    /** 来源冲突仍保存安全下限与原始提交证据；身份或安全冲突不写入。 */
    Result apply(Command command);
    /** 在调用者事务和RLS内读取已知下限；空值表示尚无设备提交证据。 */
    Optional<SecurityFloor> currentFloor(UUID tenantId, UUID projectId, UUID deviceId);

    /** 不可变模型身份与摘要解释方式。 */
    record ModelIdentity(UUID id, String digestAlgorithm, String digest, String profile) {}
    /** 上层已验证完整COMMITTED元组，转换键固定为真实提交许可ID。 */
    record Command(AuthenticatedDeviceIdentity identity, UUID deviceTypeId, ModelIdentity expectedSource,
                   ModelIdentity target, UUID transitionKey, long sourceCommittedSecurityVersion,
                   long committedSecurityVersion, String artifactSha256, boolean adoptModel) {}
    /** 保留首次裁决，不因重放改写首次时间或模型采用结果。 */
    record Result(Decision decision, UUID receiptId, UUID bindingTransitionId, Instant recordedAt,
                  SecurityFloor floor, boolean replay) {}
    /** 已知保护计数器下限及该版本唯一固件身份。 */
    record SecurityFloor(long committedSecurityVersion, String artifactSha256, UUID receiptId) {}
    /** 安全事实记录与物模型采用分别裁决。 */
    enum Decision {
        /** 来源匹配并完成模型转换。 */ CHANGED,
        /** 来源和目标本来相同，不伪造转换历史。 */ SAME_MODEL,
        /** 已保存提交事实，但当前模型或类型不满足来源CAS。 */ SOURCE_CONFLICT,
        /** 真实迟到提交只保留下限，禁止自动采用模型。 */ OBSERVED_ONLY,
        /** 物理设备或认证代际不可用，零写入。 */ IDENTITY_REJECTED,
        /** 版本下限、固件身份或同键元组冲突，零写入。 */ SECURITY_CONFLICT
    }
}
