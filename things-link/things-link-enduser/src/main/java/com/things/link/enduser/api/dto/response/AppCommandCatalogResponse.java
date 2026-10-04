package com.things.link.enduser.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.device.application.AppCommandDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** ADR0110完整目录；空列表合法，超限必须错误而非截断。
 * @param deviceId 真实设备ID @param commands 命令目录
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppCommandCatalogResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID deviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "完整命令目录，最多100项；超限整体拒绝") List<Command> commands) {
    /** @param commandKey 命令键 @param name 名称 @param description 可空说明
     * @param inputSchema 可空原JSON文本 @param outputSchema 可空原JSON文本 @param timeoutSeconds 超时秒数
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "AppDeviceCommandCatalogEntry")
    public record Command(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String commandKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) String inputSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) String outputSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int timeoutSeconds) { }

    /** @param deviceId 设备 @param entries 已授权投影 @return HTTP合同对象 */
    public static AppCommandCatalogResponse from(UUID deviceId, List<AppCommandDefinition> entries) {
        return new AppCommandCatalogResponse(deviceId, entries.stream().map(value -> new Command(
                value.commandKey(), value.name(), value.description(), value.inputSchema(), value.outputSchema(),
                value.timeoutSeconds())).toList());
    }
}
