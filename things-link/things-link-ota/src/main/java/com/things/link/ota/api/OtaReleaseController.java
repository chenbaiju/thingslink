package com.things.link.ota.api;

import com.things.link.ota.application.OtaReleaseDownloadIssuer;
import com.things.link.ota.application.OtaReleaseDownloadService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端发布物读取与短地址，不提供设备作业或制造基线授权。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "OTA 发布物", description = "管理端读取与短期下载票据")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}/release")
public class OtaReleaseController {
    /** 权威快照与当前管理资格复核。 */
    private final OtaReleaseDownloadService service;
    /** 固定版本签发及返回前二次事务复核。 */
    private final OtaReleaseDownloadIssuer issuer;

    /** 显式装配查询与网络编排，控制器不持有对象路径。 */
    public OtaReleaseController(OtaReleaseDownloadService service, OtaReleaseDownloadIssuer issuer) {
        this.service = service;
        this.issuer = issuer;
    }

    /**
     * 只投影公开密码学材料；公钥本身不代表设备信任来源。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaReleaseResponse>}
     */
    @GetMapping
    @Operation(operationId = "getOtaRelease", summary = "读取OTA管理端发布物", description = "只投影公开密码学材料；公钥本身不代表设备信任来源。")
    public ResponseEntity<OtaReleaseResponse> read(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaReleaseResponse.from(service.read(projectId, firmwareId)));
    }

    /**
     * 空正文重申领须用新键，公共层只存完成墓碑，不存短地址响应。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaReleaseDownloadResponse>}
     */
    @PostMapping("/downloads")
    @Operation(operationId = "createOtaReleaseDownload", summary = "申领OTA管理端固定版本短时下载地址", description = "空正文重申领须用新键，公共层只存完成墓碑，不存短地址响应。")
    public ResponseEntity<OtaReleaseDownloadResponse> download(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Parameter(hidden = true) @RequestBody(required = false) byte[] body) {
        if (key == null || key.isBlank() || key.length() > 128
                || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)
                || (body != null && body.length != 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaReleaseDownloadResponse.from(issuer.issue(projectId, firmwareId)));
    }
}
