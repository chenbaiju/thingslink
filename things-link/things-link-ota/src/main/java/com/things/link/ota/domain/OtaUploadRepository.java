package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.util.Optional;
import java.util.UUID;

/** 持久上传及回收边界；普通动作加入调用方事务，网络不能进入仓储。 */
public interface OtaUploadRepository {
    /** 同固件创建串行，持久唯一约束仍是最终依据。 */
    void lockCreation(UUID tenantId, UUID projectId, UUID firmwareId);
    /** 读取精确创建恢复身份。 */
    Optional<OtaUploadSession> findCreation(UUID tenantId, UUID projectId, UUID firmwareId, UUID accountId, String keyDigest);
    /** 读取唯一尚未收束会话。 */
    Optional<OtaUploadSession> findActive(UUID tenantId, UUID projectId, UUID firmwareId);
    /** 追加合法WAITING会话。 */
    void create(OtaUploadSession session);
    /** 精确项目固件范围内读取，可选锁定。 */
    Optional<OtaUploadSession> find(UUID projectId, UUID firmwareId, UUID id, boolean lock);
    /** 固件内全部上传会话按创建时刻及UUID倒序的有界游标分页。 */
    CursorPage<OtaUploadSession> page(UUID projectId, UUID firmwareId, String cursor, int limit);
    /** 领取尚未过期且未取消的接收资格。 */
    boolean claimReceive(OtaUploadSession session, UUID token);
    /** 独立短事务续租，失权不恢复。 */
    boolean renew(OtaUploadSession session, UUID token);
    /** 调用存储前持久登记可能写入。 */
    boolean markWriting(OtaUploadSession session, UUID token);
    /** 固定写入回执，不自动宣称正文验真。 */
    boolean recordVersion(OtaUploadSession session, UUID token, String versionId);
    /** 记录已完成正文核验且未取消的版本。 */
    boolean finishVerified(OtaUploadSession session, UUID token);
    /** 按真实写入边界收束或保留UNKNOWN。 */
    boolean fail(OtaUploadSession session, UUID token, String reason, boolean mayHaveWritten);
    /** 乐观登记取消意图，不能取消UNKNOWN证据。 */
    boolean requestCancel(OtaUploadSession session, long expectedRevision);
    /** 取消指定固件未收束上传。 */
    int cancelForFirmware(UUID tenantId, UUID projectId, UUID firmwareId);
    /** 同调用方事务领取一条跨域恢复事实，调用方同步审计。 */
    Optional<OtaUploadSession> claimRecovery();
    /** 释放当前恢复租约并有界延后。 */
    boolean postpone(OtaUploadSession session, UUID token, String reason);
    /** 保留写入证据并转回收阶段。 */
    boolean markCleanup(OtaUploadSession session, UUID token, String reason);
    /** 仅已证明写入终结或从未写入才完成清理。 */
    boolean finishCleanup(OtaUploadSession session, UUID token);
}
