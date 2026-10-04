package com.things.link.project.application;
import java.util.UUID;
/** ADR0172显式可信集成身份的REST准入端口；与Console共享账号/项目/租户及日计量。 */
public interface ProjectRestQuotaAdmission {
    /** 身份必须由凭据权威事实派生；此端口只做配额，不替代身份、scope或写事务许可。 */
    Decision admit(UUID ownerTenant,UUID project,UUID issuerAccount,UUID keyId,boolean write);
    record Decision(boolean allowed,long retryAfterSeconds){}
}
