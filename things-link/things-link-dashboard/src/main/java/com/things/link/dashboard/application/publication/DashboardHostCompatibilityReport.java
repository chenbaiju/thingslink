package com.things.link.dashboard.application.publication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 完整扫描后的只读报告；切换必须持锁重新检查，不能将此对象当可复用授权。
 * @param target 同次验证目标字节身份
 * @param inspected 各类已扫描事实数量
 * @param incompatibleCount 全部不兼容数量
 * @param examples 最多100个失败身份，不含正文或分享凭据
 */
public record DashboardHostCompatibilityReport(DashboardRegisteredHostQualification target,
        Map<DashboardHostCompatibility.Kind, Long> inspected, long incompatibleCount, List<Blocker> examples) {
    /** 防御复制有界结果。 */
    public DashboardHostCompatibilityReport {
        inspected = Map.copyOf(inspected); examples = List.copyOf(examples);
    }
    /** @return 仅本次完整盘点是否无冲突，不表示已激活 */
    public boolean compatible() { return incompatibleCount == 0; }
    /** @return 失败示例是否因预算被截断；扫描本身从不截断后成功 */
    public boolean examplesTruncated() { return incompatibleCount > examples.size(); }
    /** @param kind 事实类别 @param id 内部事实ID @param reason 固定安全原因 */
    public record Blocker(DashboardHostCompatibility.Kind kind, UUID id, DashboardHostCompatibility.Reason reason) { }
}
