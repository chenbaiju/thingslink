package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.util.Optional;
import java.util.UUID;

/** 持久签名发布边界，所有动作加入调用方已授权事务，网络不得进入本仓储。 */
public interface OtaPublicationRepository {
    /** 追加一次不可复用的发布请求。 */
    void create(OtaPublication publication);
    /** 项目固件范围内精确读取，可选排他锁。 */
    Optional<OtaPublication> find(UUID projectId,UUID firmwareId,UUID id,boolean lock);
    /** 固件内全部发布尝试按创建时刻及UUID倒序的有界游标分页。 */
    CursorPage<OtaPublication> page(UUID projectId,UUID firmwareId,String cursor,int limit);
    /** 读取阻止重建的当前尝试，包含UNKNOWN与COMMITTED。 */
    Optional<OtaPublication> findActive(UUID projectId,UUID firmwareId);
    /** 单条可信跨域领取；过期SIGNING只能转UNKNOWN，绝不重发。 */
    Optional<OtaPublication> claimPreparedOrSigned();
    /** 当前签名或已签名尝试续租，调用方独立短事务。 */
    boolean renew(OtaPublication publication,UUID token);
    /** 强租约登记复验通过的签名，之后不得再次调用signer。 */
    boolean recordSigned(OtaPublication publication,UUID token,byte[] spki,byte[] signature,String receipt);
    /** 确定失权或合同失败终止尝试。 */
    boolean reject(OtaPublication publication,UUID token,String reason);
    /** 可能已签名而结果不明，禁止重新发起同请求。 */
    boolean unknown(OtaPublication publication,UUID token,String reason);
    /** 原子release、固件READY、上传ADOPTED及尝试COMMITTED；中途失败全部回滚。 */
    boolean commitRelease(OtaPublication publication,UUID token,OtaRelease release);
    /** 固件唯一不可变release，查询不授予设备下载资格。 */
    Optional<OtaRelease> findRelease(UUID projectId,UUID firmwareId);
}
