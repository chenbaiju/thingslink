package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 基线头与历史原子仓储，所有动作参加已授权调用方事务。 */
public interface OtaTypeBaselineRepository {
    /** 首次与后续登记共用完整范围事务锁。 */
    void lockRegistration(UUID tenantId,UUID projectId,UUID deviceTypeId);
    /** 精确头与历史投影，缺失历史显式拒绝。 */
    Optional<OtaTypeBaselineState> find(UUID projectId,UUID deviceTypeId,boolean exclusive,boolean shared);
    /** 同事务创建头和首个历史版本。 */
    void create(OtaTypeBaselineState state);
    /** CAS通过后才追加版本并推进头；失败不留孤儿。 */
    boolean replace(long expectedRevision,OtaTypeBaselineState state);
    /** 类型基线的不可变版本历史，按登记时刻与版本倒序的有界游标分页。
     * @param projectId 精确项目
     * @param deviceTypeId 精确设备类型，调用方已确认其在本项目可见
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 只含版本、规范摘要与登记时刻的白名单游标页；空历史返回空页而不是错误
     */
    CursorPage<Version> versions(UUID projectId,UUID deviceTypeId,String cursor,int limit);

    /**
     * 历史版本的只读投影：只有对外可公开的三列。
     *
     * <p>刻意不含{@code canonical}：规范字节内嵌受控能力配置（签名Profile、RAM/Flash上限、
     * AB槽、压缩与差分方式、属性Profile）、冻结制造身份（productKey/trustDomain/rootFingerprint）
     * 与{@code evidenceReference}，这些只由启动配置提供。查询层就不取该列，能力配置因此
     * 不可能被成员可读的历史列表带出。
     *
     * @param baselineVersion 受控基线版本，同类型内严格单调
     * @param baselineHash 规范字节SHA256，数据库CHECK与历史守卫共同保证与字节一致
     * @param createdAt 该版本登记时刻，单一事务内非递减
     */
    record Version(long baselineVersion,String baselineHash,Instant createdAt) { }
}
