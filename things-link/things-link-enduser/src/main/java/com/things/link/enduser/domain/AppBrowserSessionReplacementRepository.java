package com.things.link.enduser.domain;

import java.util.UUID;

/** ADR0107受限桥只锁真实目标用户与旧refresh摘要对应用户，不授予跨租户读取或写入。 */
public interface AppBrowserSessionReplacementRepository {
    /** 目标项目SHARE许可后调用；目标必须属于当前GUC租户，两用户按PG UUID排序去重锁至事务结束。 */
    void lockUsers(UUID targetUserId, byte[] oldRefreshHash);
}
