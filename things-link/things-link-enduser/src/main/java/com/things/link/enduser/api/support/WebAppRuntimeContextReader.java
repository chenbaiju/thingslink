package com.things.link.enduser.api.support;

import com.things.link.enduser.application.WebAppRuntimeContext;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 访问合同§5四个必填、单值且禁止逗号合并的App运行上下文头读取器。 */
public final class WebAppRuntimeContextReader {
    /** 应用公开键头。 */ public static final String APPLICATION_KEY = "X-Application-Key";
    /** 应用版本头。 */ public static final String APPLICATION_VERSION = "X-Application-Version";
    /** 应用发布代次头。 */ public static final String APPLICATION_REVISION = "X-Application-Revision";
    /** 看板版本头。 */ public static final String DASHBOARD_VERSION = "X-Dashboard-Version";

    /** 禁止实例化无状态HTTP边界工具。 */
    private WebAppRuntimeContextReader() { }

    /**
     * @param request 当前Servlet请求
     * @return 完成规范语法验证的运行上下文
     */
    public static WebAppRuntimeContext read(HttpServletRequest request) {
        String appKey = single(request, APPLICATION_KEY);
        if (!appKey.matches("app_[0-9a-f]{32}")) throw invalid();
        UUID applicationVersion = uuid(single(request, APPLICATION_VERSION));
        UUID dashboardVersion = uuid(single(request, DASHBOARD_VERSION));
        String revisionText = single(request, APPLICATION_REVISION);
        if (!revisionText.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            return new WebAppRuntimeContext(appKey, applicationVersion,
                    Long.parseLong(revisionText), dashboardVersion);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    /** 要求一个物理单值头；容器合并的逗号形式也拒绝。 */
    private static String single(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()
                || values.getFirst().indexOf(',') >= 0) throw invalid();
        return values.getFirst();
    }

    /** 解析规范小写UUID。 */
    private static UUID uuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw new IllegalArgumentException("UUID非规范文本");
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 缺失、重复、合并或格式错误运行头统一10001。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
