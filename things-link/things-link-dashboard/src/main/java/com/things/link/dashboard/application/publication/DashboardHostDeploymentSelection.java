package com.things.link.dashboard.application.publication;

/**
 * 控制面显式选择，仅在完整制品身份匹配后才能成为当前资格。
 * @param revision 数据库正数代次
 * @param hostVersion 已登记的精确宿主版本
 * @param artifactDigest 已选择的ZIP摘要
 * @param sourceDigest 已选择的源码收据摘要
 */
public record DashboardHostDeploymentSelection(long revision, String hostVersion,
                                              String artifactDigest, String sourceDigest) {
    /** 不接受半空选择，避免坏状态降级成默认宿主。 */
    public DashboardHostDeploymentSelection {
        if (revision <= 0 || hostVersion == null || !java.util.Set.of("1.0.0", "1.1.0", "1.1.1").contains(hostVersion)
                || artifactDigest == null || !artifactDigest.matches("[a-f0-9]{64}")
                || sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("宿主选择事实无效");
        }
    }
}
