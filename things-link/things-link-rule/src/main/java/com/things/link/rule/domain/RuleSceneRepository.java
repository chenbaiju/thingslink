package com.things.link.rule.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 场景定义、不可变版本事实与执行事实的持久化端口。 */
public interface RuleSceneRepository {

    /** 原事务内串行化场景管理与执行。 */
    Optional<RuleScene> lock(UUID projectId, UUID sceneId);
    /** 按稳定创建时间与ID进行有界目录查询。 */
    List<RuleScene> search(UUID projectId, String name, String status, Instant beforeTime, UUID beforeId, int limit);
    /** 按版本号降序读取有界历史。 */
    List<RuleSceneVersion> history(UUID projectId, UUID sceneId, Long beforeVersion, int limit);
    /** ACTIVE原子暂停，保留活动版本与执行事实。 */
    boolean pause(UUID projectId, UUID sceneId, long expectedVersion);

    /** @return 创建定义和首个版本是否各写入一行 */
    boolean create(RuleScene scene, RuleSceneVersion initialVersion);

    /** @return 当前项目内未删除场景 */
    Optional<RuleScene> find(UUID projectId, UUID sceneId);

    /** @return 指定场景的版本；跨场景或跨项目返回空 */
    Optional<RuleSceneVersion> findVersion(UUID projectId, UUID sceneId, UUID versionId);

    /** @return 按版本号升序排列的完整版本历史 */
    List<RuleSceneVersion> versions(UUID projectId, UUID sceneId);

    /** @return 下一展示版本号；调用方必须先成功取得定义 CAS 写资格 */
    long nextVersionNumber(UUID projectId, UUID sceneId);

    /** @return 定义 CAS 成功且新版本已追加 */
    boolean revise(RuleScene replacement, long expectedVersion, RuleSceneVersion newVersion);

    /** @return 活动版本指针 CAS 是否成功 */
    boolean activate(UUID projectId, UUID sceneId, UUID versionId, long expectedVersion);

    /** @return 软删除 CAS 是否成功 */
    boolean softDelete(UUID projectId, UUID sceneId, long expectedVersion);

    /** @return 按幂等键查找执行事实；跨项目或跨场景返回空 */
    Optional<RuleSceneExecution> findExecutionByKey(UUID projectId, UUID sceneId, String idempotencyKey);

    /** @return 幂等插入执行事实；唯一键 (project_id, scene_id, idempotency_key) 冲突时不覆盖 */
    boolean insertExecution(RuleSceneExecution execution);

    /** @return 指定执行事实；跨项目返回空 */
    Optional<RuleSceneExecution> findExecution(UUID projectId, UUID executionId);
}
