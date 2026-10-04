package com.things.link.ota.domain;

import java.util.UUID;

/** ADR0204原项目许可之后、领域锁之前的窄来源端口，不依赖公开集成领域。 */
@FunctionalInterface
public interface OtaJobCompletionCapture {
    /** 在当前实际事务和完整RLS下捕获可写项目代次；关闭或不可写时不启用来源。 */
    void capture(UUID tenant, UUID project);
}
