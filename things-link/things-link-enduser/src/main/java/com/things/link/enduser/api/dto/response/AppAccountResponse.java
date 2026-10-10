package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppAccount;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * App本人账户资料，与Console身份分离。
 * @param id 终端用户标识
 * @param username 用户名
 * @param displayName 可空显示名称
 * @param createdAt 创建时刻
 * @param projectRole 当前项目角色
 * @param passwordChangeAllowed 当前项目允许改密；提交时仍需复验
 */
@Schema(description = "App本人账户资料")
public record AppAccountResponse(
        @Schema(description = "终端用户标识", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @Schema(description = "显示名称", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(description = "创建时刻", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED) String createdAt,
        @Schema(description = "当前项目角色", requiredMode = Schema.RequiredMode.REQUIRED) String projectRole,
        @Schema(description = "当前项目允许改密，提交时仍复验", requiredMode = Schema.RequiredMode.REQUIRED) boolean passwordChangeAllowed) {
    /** @param account 已复验的本人资料 @return 不包含认证秘密的公开响应 */
    public static AppAccountResponse from(AppAccount account) {
        return new AppAccountResponse(account.id().toString(), account.username(), account.displayName(),
                account.createdAt().toString(), account.projectRole().name(), account.passwordChangeAllowed());
    }
}
