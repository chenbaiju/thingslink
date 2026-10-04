package com.things.link.rule.domain;

import com.things.link.shared.page.CursorPage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 手动场景执行事实的只读端口。
 *
 * <p>与 S9-4 的写路径（{@code RuleSceneRepository}）分离：本端口只读执行事实与动作关联投递事实，全部在项目
 * RLS 范围内执行。</p>
 */
public interface RuleSceneExecutionReadRepository {

    /** @return 执行事实分页，按落库时刻倒序 */
    CursorPage<RuleSceneExecutionSummary> find(RuleSceneExecutionQuery query);

    /** @return 指定执行事实投影；跨项目返回空 */
    Optional<RuleSceneExecutionSummary> findSummary(UUID projectId, UUID executionId);

    /** @return 该次执行的已渲染通知投递事实，按创建时刻升序 */
    List<NotificationDeliverySummary> findNotifications(UUID projectId, UUID executionId);

    /** @return 该次执行的设备命令/属性设置投递事实，按创建时刻升序 */
    List<DeviceActionDeliverySummary> findDeviceActions(UUID projectId, UUID executionId);

    /** @return 当前项目内已产生过执行事实的场景选项，按名称升序 */
    List<RuleOption> findSceneOptions(UUID projectId);
}
