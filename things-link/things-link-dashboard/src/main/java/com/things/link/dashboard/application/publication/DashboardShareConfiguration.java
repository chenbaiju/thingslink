package com.things.link.dashboard.application.publication;

/**
 * 管理者可见的最小分享宿主事实，不暴露受管目录、制品摘要或凭据。
 * @param available 当前分享运行配置与受管宿主是否均可用
 * @param hostOrigin 已验证的唯一公开Origin，不可用时为空
 * @param hostVersion 实际受管宿主版本，不可用时为空
 * @param hostCompatibility 仅覆盖当前稳定版本的半开范围，不可用时为空
 */
public record DashboardShareConfiguration(boolean available, String hostOrigin, String hostVersion,
        HostCompatibility hostCompatibility) {
    /** @param minInclusive 当前稳定版本 @param maxExclusive 下一合法三段版本 */
    public record HostCompatibility(String minInclusive, String maxExclusive) { }

    /** 不可用必须全空，不能泄露部分配置或误导UI提前签发。 */
    public static DashboardShareConfiguration unavailable() {
        return new DashboardShareConfiguration(false, null, null, null);
    }
}
