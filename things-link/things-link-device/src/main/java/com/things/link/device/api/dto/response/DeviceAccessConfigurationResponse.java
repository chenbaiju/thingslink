package com.things.link.device.api.dto.response;

import com.things.link.device.application.DeviceAccessConfigurationView;
import com.things.link.shared.message.TransportProtocol;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 冻结的七字段配置响应；两种版本以字符串避免JavaScript精度损失。
 * @param protocol 当前协议 @param enabled 当前开关 @param configVersion 配置版本
 * @param credentialVersion 凭据版本 @param configured 是否有持久配置
 * @param allowedProtocols 类型允许的协议 @param canManage 展示用管理资格，不能替代PUT重新授权
 */
public record DeviceAccessConfigurationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransportProtocol protocol,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^(0|[1-9][0-9]{0,18})$") String configVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^(0|[1-9][0-9]{0,18})$") String credentialVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean configured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransportProtocol> allowedProtocols,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canManage) {
    /** @param view 原事务的无秘密视图 @return 精度安全响应 */
    public static DeviceAccessConfigurationResponse from(DeviceAccessConfigurationView view) {
        return new DeviceAccessConfigurationResponse(view.protocol(), view.enabled(), Long.toString(view.configVersion()),
                Long.toString(view.credentialVersion()), view.configured(), view.allowedProtocols(), view.canManage());
    }
}
