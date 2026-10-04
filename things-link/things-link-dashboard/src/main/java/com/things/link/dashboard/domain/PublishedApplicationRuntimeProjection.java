package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在可信项目RLS范围内重新读取的当前应用公开投影。
 *
 * <p>该值只包含resolve需要的应用侧事实；当前指针与精确版本连接由仓储SQL证明，不能从管理名称或
 * 可变草稿拼接公开展示名称。</p>
 *
 * @param tenantId 应用所属项目的租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 应用内部ID
 * @param appKey 创建后不可变的公开定位符
 * @param displayName 当前不可变应用版本中的公开展示名称
 */
public record PublishedApplicationRuntimeProjection(
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        String appKey,
        String displayName) {

    /** ADR0096冻结的公开定位符语法。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");

    /** 复核持久投影的身份和公开文本，损坏事实必须以内部异常暴露而不能伪装不可用。 */
    public PublishedApplicationRuntimeProjection {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
        if (!APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("持久应用appKey不符合冻结语法");
        }
        int codePoints = displayName.codePointCount(0, displayName.length());
        boolean onlyWhitespace = displayName.codePoints().allMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
        boolean containsControl = displayName.codePoints().anyMatch(codePoint ->
                codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F);
        if (codePoints < 1 || codePoints > 80 || onlyWhitespace || containsControl) {
            throw new IllegalArgumentException("持久应用displayName违反Title合同");
        }
    }
}
