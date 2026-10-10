package com.things.link.enduser.application;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已签发会话的可信关联，全部由服务端生成，不从注册正文接收。 */
@Schema(description="已签发会话的可信身份；平台实例未配置时不具备推送接入资格")
public record AppSessionIdentity(
        @Schema(description="稳定平台实例，未配置时为空",types={"string","null"},format="uuid") UUID backendInstanceId,
        @Schema(description="可信租户") UUID tenantId,
        @Schema(description="终端用户") UUID appUserId,
        @Schema(description="当前项目") UUID projectId,
        @Schema(description="当前刷新族，与JWT的sid一致") UUID sessionId,
        @Schema(description="跨项目换签保持的登录会话组") UUID sessionGroupId) {}
