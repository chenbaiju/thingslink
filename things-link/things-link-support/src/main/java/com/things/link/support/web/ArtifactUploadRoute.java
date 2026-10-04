package com.things.link.support.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/** ADR0117原始上传精确路由，仅豁免正文缓存，不改变认证或项目授权。 */
public final class ArtifactUploadRoute {
    /** UUID必须完整分组，不接受缩写、路径参数或编码分隔符。 */
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /** 会话集合只退出公共幂等，仍按JSON上限读取。 */
    private static final Pattern CREATE = Pattern.compile("^/api/v1/projects/" + UUID
            + "/ota/firmwares/" + UUID + "/uploads$");
    /** 只有一次消费的完整content路径才是流式正文。 */
    private static final Pattern CONTENT = Pattern.compile("^/api/v1/projects/" + UUID
            + "/ota/firmwares/" + UUID + "/uploads/" + UUID + "/content$");
    /** 工具类不实例化。 */
    private ArtifactUploadRoute() { }
    /** 容器contextPath不属于业务路由。 */
    public static boolean isContent(HttpServletRequest request) {
        return isContent(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()));
    }
    /** 方法与完整路径同时匹配，拒绝相邻路由。 */
    public static boolean isContent(String method, String path) {
        return "PUT".equals(method) && CONTENT.matcher(path).matches();
    }
    /** 创建使用领域恢复映射；取消不命中此谓词。 */
    public static boolean isCreation(String method, String path) {
        return "POST".equals(method) && CREATE.matcher(path).matches();
    }
}
