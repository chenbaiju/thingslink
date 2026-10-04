package com.things.link.ota.application;

import com.things.link.ota.domain.OtaRollbackRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 已锁完整图内的提交方向门禁，SQL最终采用仍独立复核。 */
@Component
public final class OtaRollbackDirectionGuard {
    /** 原固定回退及不可变赢家。 */ private final OtaRollbackRepository operations;
    /** 只读方向，不产生发送授权。 */
    public OtaRollbackDirectionGuard(OtaRollbackRepository operations) { this.operations=operations; }
    /** 任意历史操作存在不可清除冲突时拒绝设备继续执行。 */
    public boolean deviceAllowed(UUID deviceId) {
        return deviceId != null && !operations.deviceConflicted(deviceId);
    }
    /** 当前作业的提交赢家不能绕过同设备历史冲突。 */
    public boolean commitAllowed(UUID jobId,int attemptNo,UUID deviceId) {
        if (!deviceAllowed(deviceId)) return false;
        var operation=operations.findForJob(jobId,attemptNo).orElse(null);
        if(operation==null) return true;
        var control=operations.control(operation.id()).orElseThrow(
                ()->new IllegalStateException("固定回退缺少方向控制事实"));
        return control.commitWonReportId()!=null&&control.acceptedReportId()==null&&control.conflictedAt()==null;
    }
}
