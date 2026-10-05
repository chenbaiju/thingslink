package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaUploadProcessor;
import com.things.link.ota.application.OtaUploadService;
import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.ota.domain.OtaUploadErrorCode;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.BoundedArtifactReceiver;
import com.things.link.support.tenant.ScopedTenantWork;
import com.things.link.support.trace.TraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 上传会话HTTP边界，网络处理不持数据库事务且回调显式恢复可信范围。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "OTA 固件上传", description = "上传会话、分块内容及完成状态")
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}/uploads")
public class OtaUploadController {
    /** 上传短事务及授权。 */ private final OtaUploadService service;
    /** 原始正文非阻塞技术接收。 */ private final BoundedArtifactReceiver receiver;
    /** 固定对象处理及租约心跳。 */ private final OtaUploadProcessor processor;
    /** OTA部署权限守卫。 */ private final OtaAuthorization authorization;
    /** 严格JSON输入。 */ private final OtaUploadRequestParser parser;
    /** 异步线程显式响应序列化。 */ private final ObjectMapper mapper;
    /** 显式注入生产端口。 */
    public OtaUploadController(OtaUploadService service, BoundedArtifactReceiver receiver,
            OtaUploadProcessor processor, OtaAuthorization authorization, OtaUploadRequestParser parser, ObjectMapper mapper) {
        this.service = service; this.receiver = receiver; this.processor = processor;
        this.authorization = authorization; this.parser = parser; this.mapper = mapper;
    }
    /**
     * 创建及重复请求均回到领域授权和恢复映射。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaUploadResponse>}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOtaUpload", summary = "创建或恢复固件上传会话", description = "创建及重复请求均回到领域授权和恢复映射。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "稳定会话",
            content = @Content(schema = @Schema(implementation = OtaUploadResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaUploadRequestParser.CreateRequest.class)))
    public ResponseEntity<OtaUploadResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        requireManage(projectId);
        var input = parser.create(body);
        var result = OtaUploadResponse.from(service.create(projectId, firmwareId, key,
                input.expectedLength(), input.expectedSha256()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/ota/firmwares/"
                + firmwareId + "/uploads/" + result.id())).body(result);
    }
    /**
     * 精确项目状态查询，不输出存储或租约标识。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param sessionId 上传会话标识
     * @return 当前接口的操作结果，响应结构见 {@code OtaUploadResponse}
     */
    @GetMapping("/{sessionId}")
    @Operation(operationId = "getOtaUpload", summary = "读取固件上传会话", description = "精确项目状态查询，不输出存储或租约标识。")
    public OtaUploadResponse find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
                                  @io.swagger.v3.oas.annotations.Parameter(description = "上传会话标识") @PathVariable UUID sessionId) {
        return OtaUploadResponse.from(service.find(projectId, firmwareId, sessionId));
    }
    /**
     * 成员读取固件内上传会话历史，最新在前；空历史返回空页，父固件不存在仍404。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping
    @Operation(operationId = "listOtaUploads", summary = "固件上传会话历史", description = "成员读取固件内上传会话历史，最新在前；空历史返回空页，父固件不存在仍404。")
    public ResponseEntity<CursorPage<OtaUploadResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.history(projectId, firmwareId, cursor, limit)
                .map(OtaUploadResponse::from));
    }
    /**
     * 取消登记不声明物理对象已回收；公共幂等墓碑仍适用。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param sessionId 上传会话标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code OtaUploadResponse}
     */
    @PostMapping(value = "/{sessionId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelOtaUpload", summary = "登记固件上传取消", description = "取消登记不声明物理对象已回收；公共幂等墓碑仍适用。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaUploadRequestParser.CancelRequest.class)))
    public OtaUploadResponse cancel(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @io.swagger.v3.oas.annotations.Parameter(description = "上传会话标识") @PathVariable UUID sessionId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        requireManage(projectId);
        return OtaUploadResponse.from(service.cancel(projectId, firmwareId, sessionId, key,
                parser.cancel(body).expectedRevision()));
    }
    /**
     * 消费一次接收身份；Servlet回调不能继承安全链清理后的ThreadLocal。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param sessionId 上传会话标识
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     */
    @PutMapping(value = "/{sessionId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "uploadOtaContent", summary = "有界流式接收固件内容", description = "消费一次接收身份；Servlet回调不能继承安全链清理后的ThreadLocal。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "上传对象复验状态",
            content = @Content(schema = @Schema(implementation = OtaUploadResponse.class)))
    public void content(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId, @io.swagger.v3.oas.annotations.Parameter(description = "上传会话标识") @PathVariable UUID sessionId,
                        HttpServletRequest request, HttpServletResponse response) {
        requireManage(projectId);
        OtaUploadSession session = service.prepare(projectId, firmwareId, sessionId);
        TenantScope scope = TenantContext.require();
        String trace = TraceContext.current();
        OtaUploadProcessor.Lease lease;
        try { lease = processor.begin(session); }
        catch (RuntimeException failure) {
            try { service.fail(session, "RECEIVE_START_FAILED", false); }
            catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            throw failure;
        }
        receiver.receive(request, response, session.expectedLength(), session.expectedSha256(),
                new BoundedArtifactReceiver.Work() {
                    /** 只读本地失租信号，不能阻塞接收watchdog。 */
                    @Override public boolean cancelled() { return lease.cancelled(); }
                    /** 在显式可信范围中处理对象，退出时释放本次租约。 */
                    @Override public void completed(Path source, BooleanSupplier cancelled) throws IOException {
                        try (lease) {
                            OtaUploadSession result = ScopedTenantWork.call(scope,
                                    () -> processor.process(session, source, lease, cancelled));
                            write(response, 200, OtaUploadResponse.from(result));
                        } catch (BusinessException exception) {
                            error(response, exception.errorCode(), trace);
                        } catch (RuntimeException exception) {
                            error(response, CommonErrorCode.INTERNAL_ERROR, trace);
                        }
                    }
                    /** 接收阶段失败尚未发起对象写入，固定分类并回收租约。 */
                    @Override public void failed(BoundedArtifactReceiver.Failure failure) throws IOException {
                        try (lease) {
                            ScopedTenantWork.run(scope, () -> service.fail(session,
                                    "RECEIVE_" + failure.reason().name(), false));
                        } catch (RuntimeException exception) {
                            error(response, CommonErrorCode.INTERNAL_ERROR, trace);
                            return;
                        }
                        ErrorCode code = switch (failure.reason()) {
                            case BUSY -> CommonErrorCode.TOO_MANY_REQUESTS;
                            case LENGTH_MISMATCH, DIGEST_MISMATCH -> OtaUploadErrorCode.CONTENT_MISMATCH;
                            default -> OtaUploadErrorCode.RECEIVE_FAILED;
                        };
                        error(response, code, trace);
                    }
                });
    }
    /** 异步错误统一白名单，底层异常和供应商文本不外泄。 */
    private void error(HttpServletResponse response, ErrorCode code, String trace) throws IOException {
        write(response, code.httpStatus(), new ApiError(code.code(), code.defaultMessage(), trace, List.of()));
    }
    /** 容器完成async前写入一次响应，不flush已提交的失败连接。 */
    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
    /** 管理角色检查先于信封解析，领域短事务仍再次复核。 */
    private void requireManage(UUID project) {
        if (!authorization.mayDeploy(project)) {
            throw new BusinessException(OtaFirmwareErrorCode.FORBIDDEN);
        }
    }
}
