package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.response.DashboardShareContextResponse;
import com.things.link.dashboard.api.dto.response.DashboardShareSchemaResponse;
import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 分享版本读取完整入口；不复用Console/App身份，不开放未实施的数据或WS路由。
 * context/schema各自重新检查capability和生命周期，最终UTF-8有界编码后才交安全过滤器计量发送。
 */
@RestController
@RequestMapping("/api/v1/shares/{shareId}")
@Tag(name = "匿名看板分享", description = "独立capability的只读精确版本入口")
@SecurityScheme(name = "shareCapability", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Share-Token", description = "单值规范sh_凭据；不接受Console/App Authorization")
@SecurityRequirement(name = "shareCapability")
@ApiResponse(responseCode = "400", description = "输入格式非法",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "Referer不匹配或路由不允许",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "404", description = "分享能力或精确资源当前不可用",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "429", description = "共享保护预算耗尽；错误正文也无字节额度时为空",
        headers = { @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                @Header(name = "Retry-After", schema = @Schema(type = "string", allowableValues = "1")) },
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "503", description = "分享未启用或保护依赖不可用；错误正文无法安全计量时为空",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
public class DashboardShareRuntimeController {
    /** ADR0101 context上限16KiB，以最终UTF-8正文而非Java字符计。 */
    static final int CONTEXT_LIMIT = 16 * 1024;
    /** ADR0100/0101完整Schema包装上限768KiB，根500KiB由领域复验。 */
    static final int SCHEMA_LIMIT = 768 * 1024;
    /** 显式UTF-8，避免byte[]被消息转换器标成任意二进制。 */
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    /** 每次真实事务重新验证的公开应用用例。 */
    private final DashboardShareRuntimeService service;
    /** 使用与HTTP全局相同的Jackson配置，一次编码的字节用于预算与实际发送。 */
    private final ObjectMapper mapper;

    /** 所有运行依赖显式装配，缺失依赖不能转成无鉴权静态页面。 */
    public DashboardShareRuntimeController(DashboardShareRuntimeService service, ObjectMapper mapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 读取有限候选和DB历史锚点，不返回内部项目身份或原凭据。 */
    @GetMapping(value = "/context", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDashboardShareContext", summary = "读取匿名分享上下文")
    @ApiResponse(responseCode = "200", description = "分享当前可运行，context不超过16KiB",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardShareContextResponse.class)))
    public ResponseEntity<byte[]> context(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        return guardedResponse(() -> DashboardShareContextResponse.from(service.context(principal)), CONTEXT_LIMIT);
    }

    /** 返回分享冻结的完整精确Schema，不跟随Dashboard当前指针切换版本。 */
    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDashboardShareSchema", summary = "读取匿名分享精确Schema")
    @ApiResponse(responseCode = "200", description = "全部页面及派生清单，包装不超过768KiB",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardShareSchemaResponse.class)))
    public ResponseEntity<byte[]> schema(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        return guardedResponse(() -> DashboardShareSchemaResponse.from(service.schema(principal)), SCHEMA_LIMIT);
    }

    /** 无query/body；只接受安全链内部建立且与路径一致的独立Share身份。 */
    private static DashboardSharePrincipal principal(String shareId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        Object value = request.getAttribute(DashboardSharePrincipal.class.getName());
        if (!(value instanceof DashboardSharePrincipal principal) || !principal.shareId().toString().equals(shareId)) {
            throw new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
        }
        return principal;
    }

    /** 事务代理创建/提交和DTO映射发生在应用方法守卫外，必须在MVC通用500处理之前归一依赖失败。 */
    private ResponseEntity<byte[]> guardedResponse(Supplier<Object> payload, int limit) {
        try {
            return response(payload.get(), limit);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            BusinessException mapped = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            mapped.initCause(failure);
            throw mapped;
        }
    }

    /** 限额内编码完整后才构造响应，超限没有半个Schema泄漏到网络。 */
    private ResponseEntity<byte[]> response(Object payload, int limit) {
        BoundedJsonOutput output = new BoundedJsonOutput(limit);
        try {
            mapper.writeValue(output, payload);
        } catch (RuntimeException exception) {
            BusinessException failure = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            failure.initCause(exception);
            throw failure;
        }
        return ResponseEntity.ok().contentType(JSON_UTF8).cacheControl(CacheControl.noStore()).body(output.bytes());
    }

    /** 固定上限的编码容器，先检查长度再分配或追加，不能编码后才发现无界分配。 */
    static final class BoundedJsonOutput extends OutputStream {
        /** context/schema确定的单次编码上限。 */
        private final int limit;
        /** 至多limit字节，初始小容量避免每个小响应预分配768KiB。 */
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        /** 建立一个已冻结上限的编码缓冲。 */
        BoundedJsonOutput(int limit) { this.limit = limit; }
        /** 单字节追加也必须先经过预算，防止编码器绕过块写检查。 */
        @Override public void write(int value) {
            requireCapacity(1);
            delegate.write(value);
        }
        /** 对整块先检查预算，失败不写任何块前缀。 */
        @Override public void write(byte[] source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length);
            requireCapacity(length);
            delegate.write(source, offset, length);
        }
        /** 用减法避免length加法溢出，超限按运行依赖不可用分类。 */
        private void requireCapacity(int additional) {
            if (additional > limit - delegate.size()) {
                throw new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            }
        }
        /** 最终独立字节数组用于实际HTTP输出与保护计量。 */
        byte[] bytes() { return delegate.toByteArray(); }
    }
}
