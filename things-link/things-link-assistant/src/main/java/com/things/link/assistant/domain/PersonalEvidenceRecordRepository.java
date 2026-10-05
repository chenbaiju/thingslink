package com.things.link.assistant.domain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** 当前权限由应用层复核，所有存储操作仍显式绑定双轴及创建者。 */
public interface PersonalEvidenceRecordRepository {
    /** 保存正文并由数据库确定30天可见期，必须在持项目锁的写事务内调用。 */
    PersonalEvidenceRecord insert(UUID id, UUID tenant, UUID project, UUID creator, UUID device, UUID model, String hash, String content);
    /** 最多100条有效记录，列表不选择正文。 */
    List<PersonalEvidenceRecord> list(UUID tenant, UUID project, UUID creator);
    /** 只读取本人有效记录，不因角色为管理员而扩大范围。 */
    Optional<PersonalEvidenceRecord> find(UUID tenant, UUID project, UUID creator, UUID id);
    /** 当前写事务中计算本人有效容量。 */
    int count(UUID tenant, UUID project, UUID creator);
    /** 本人显式删除；不关联或重置调用台账。 */
    boolean delete(UUID tenant, UUID project, UUID creator, UUID id);
    /** 每次最多100条本人过期行，不宣称无活动时自动物理清理。 */
    int deleteExpired(UUID tenant, UUID project, UUID creator);
}
