package com.things.link.issuer.application;

import java.util.UUID;

/** 自部署申请审核资格仅由发行方数据库给出，不继承商业调整身份。 */
public interface ShcReviewerRepository {
    /** 写路径必须在可写 READ COMMITTED 事务中持锁复核。 */
    boolean enabled(UUID actor, boolean holdLock);
}
