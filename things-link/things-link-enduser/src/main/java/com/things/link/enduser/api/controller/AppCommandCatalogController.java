package com.things.link.enduser.api.controller;

import com.things.link.device.application.AppCommandCatalogDataPlaneService;
import com.things.link.enduser.api.dto.response.AppCommandCatalogResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.AppCommandCatalogService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 独立App控制目录，不改旧命令受理/状态路径；ADR0110先完整有界编码才发送。 */
@RestController
public class AppCommandCatalogController {
    /** 完整包装UTF-8上限，包含转义膨胀及元数据。 */
    private static final int RESPONSE_LIMIT = 256 * 1024;
    /** 同事务授权和投影。 */
    private final AppCommandCatalogService service;
    /** 复用全局JSON编码规则。 */
    private final ObjectMapper mapper;
    /** @param service 目录用例 @param mapper JSON编码器 */
    public AppCommandCatalogController(AppCommandCatalogService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }
    /** @param jwt 真实App身份 @param deviceId 设备 @param response 提前设置错误响应缓存策略 @return 有界目录 */
    @GetMapping(value = "/api/v1/app/devices/{deviceId}/command-definitions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAppDeviceCommandCatalog", summary = "读取App设备可控制命令目录",
            description = "PRIMARY/MEMBER可用；仅预览当前定义，不锁定后续提交版本。")
    @ApiResponse(responseCode = "200", description = "完整目录，最多100项且UTF-8包装不超过256KiB",
            content = @Content(schema = @Schema(implementation = AppCommandCatalogResponse.class)))
    @ApiResponse(responseCode = "503", description = "目录数量或字节超限（30064）",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
                                        HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        AppCommandCatalogResponse payload = AppCommandCatalogResponse.from(deviceId,
                service.list(AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt), deviceId));
        BoundedOutput output = new BoundedOutput();
        try {
            mapper.writeValue(output, payload);
        } catch (RuntimeException exception) {
            BusinessException failure = AppCommandCatalogDataPlaneService.unavailable();
            failure.initCause(exception);
            throw failure;
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(output.bytes());
    }
    /** 限制实际编码过程，禁止先无界writeValueAsBytes再检查。 */
    static final class BoundedOutput extends OutputStream {
        /** 只有通过预算检查的字节才能入缓冲。 */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        /** {@inheritDoc} */
        @Override public void write(int value) {
            requireCapacity(1);
            buffer.write(value);
        }
        /** {@inheritDoc} */
        @Override public void write(byte[] bytes, int offset, int length) {
            requireCapacity(length);
            buffer.write(bytes, offset, length);
        }
        /** @param length 本次编码字节，超过ADR0110固定上限立即整体拒绝 */
        private void requireCapacity(int length) {
            if (length < 0 || length > RESPONSE_LIMIT - buffer.size()) {
                throw AppCommandCatalogDataPlaneService.unavailable();
            }
        }
        /** @return 仅完整编码成功后取得正文 */
        byte[] bytes() { return buffer.toByteArray(); }
    }
}
