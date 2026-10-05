package com.things.link.assistant.domain;
import java.util.Optional;
import java.util.UUID;
/** assistant 自有密文持久化端口；调用方必须持有项目管理锁与事务 RLS。 */
public interface ModelCredentialRepository {
    /**
     * 查询指定隔离范围内的密文，不解密或回显秘密。
     * @param tenant 归属租户
     * @param project 归属项目
     * @return 当前密文配置，不存在时为空
     */
    Optional<ModelCredential> find(UUID tenant, UUID project);
    /**
     * 按期望版本原子保存密文、启停或移除状态。
     * @param credential 待保存的可信配置记录
     * @param expectedRevision 当前版本，零表示首次配置
     * @return 版本匹配并成功保存时为真
     */
    boolean save(ModelCredential credential, long expectedRevision);
}
