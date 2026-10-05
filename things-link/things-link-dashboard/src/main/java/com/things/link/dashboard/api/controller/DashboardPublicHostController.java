package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.application.DashboardPublicHostService;
import com.things.link.dashboard.application.publication.DashboardPublicHostUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADR0149公开静态壳，不读取业务身份，响应只含同次验证内容。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "应用静态文件", description = "受控公开静态壳与白名单资源，当前公开部署验收暂停")
@RestController
public class DashboardPublicHostController {
    /** 控制器不接触文件系统或operator权限，只映射公开交付结果。 */
    private final DashboardPublicHostService hosts;
    /** @param hosts 固定路由与事务内容服务 */
    public DashboardPublicHostController(DashboardPublicHostService hosts) { this.hosts = hosts; }

    /**
     * 读取受控应用静态壳或白名单资源；拒绝编码路径、穿越和未登记文件。
     *
     * @param request 原始路径用于拒绝编码/穿越，不作为任意磁盘路径
     * @return 精确公开字节或空错误
     */
    @io.swagger.v3.oas.annotations.Operation(operationId = "getApplicationStaticContent", summary = "读取受控应用静态文件；拒绝编码路径、穿越及未登记资源", description = "读取受控应用静态文件；拒绝编码路径、穿越及未登记资源。")
    @GetMapping({"/app", "/app/**"})
    public ResponseEntity<byte[]> read(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/app")) {
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) return error(404);
            return ResponseEntity.status(308).header(HttpHeaders.LOCATION, "/app/").header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        try {
            var file = hosts.read(path, request.getQueryString());
            if (file.isEmpty()) return error(404);
            var value = file.orElseThrow();
            var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,
                    value.immutable() ? "public, max-age=31536000, immutable" : "no-store")
                    .header(HttpHeaders.CONTENT_TYPE, contentType(value.file()))
                    .header("X-Content-Type-Options", "nosniff").header("X-Frame-Options", "SAMEORIGIN");
            if (value.file().equals("sw.js")) response.header("Service-Worker-Allowed", "/app/");
            return response.body(value.bytes());
        } catch (DashboardPublicHostUnavailableException unavailable) { return error(503); }
    }
    /** 错误正文为空且不缓存，避免泄露资格失败细节。 */
    private static ResponseEntity<byte[]> error(int status) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
    /** 白名单扩展名映射固定媒体类型，不能由文件正文或用户参数决定。 */
    private static String contentType(String file) {
        if (file.endsWith(".html")) return "text/html; charset=utf-8";
        if (file.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (file.endsWith(".css")) return "text/css; charset=utf-8";
        if (file.endsWith(".json")) return "application/json";
        if (file.endsWith(".webmanifest")) return "application/manifest+json";
        if (file.endsWith(".png")) return "image/png";
        if (file.endsWith(".svg")) return "image/svg+xml";
        if (file.endsWith(".woff2")) return "font/woff2";
        throw new IllegalArgumentException("公开文件类型未登记");
    }
}
