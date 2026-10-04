package com.things.link.enduser.api.dto.request;

import com.things.link.enduser.domain.AppUserDevice;
import jakarta.validation.constraints.NotNull;

/**
 * 签发 SHARE 令牌的目标角色请求。
 *
 * @param targetRole 只允许 MEMBER 或 READ_ONLY
 */
public record IssueDeviceShareTokenRequest(
        @NotNull ShareTargetRole targetRole) {

    /** SHARE API 显式排除 PRIMARY，避免客户端构造越权枚举值。 */
    public enum ShareTargetRole {
        /** 可操作设备的普通成员。 */
        MEMBER,
        /** 只能读取设备数据的只读成员。 */
        READ_ONLY
    }

    /**
     * 转换为领域关系角色。
     *
     * @return MEMBER 或 READ_ONLY
     */
    public AppUserDevice.RelationRole relationRole() {
        return AppUserDevice.RelationRole.valueOf(targetRole.name());
    }
}
