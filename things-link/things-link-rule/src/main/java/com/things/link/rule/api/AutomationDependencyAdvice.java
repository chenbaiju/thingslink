package com.things.link.rule.api;
import com.things.link.rule.api.controller.AutomationController;
import com.things.link.rule.api.controller.AutomationExecutionController;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.support.trace.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** 自动化HTTP临时数据库失败使用冻结503码；不把完整性/程序错误误报为可重试。 */
@RestControllerAdvice(assignableTypes={AutomationController.class,AutomationExecutionController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AutomationDependencyAdvice {
    @ExceptionHandler({TransientDataAccessException.class,RecoverableDataAccessException.class,DataAccessResourceFailureException.class})
    public ResponseEntity<ApiError> unavailable(){
        var code=RuleErrorCode.AUTOMATION_DEPENDENCY_UNAVAILABLE;
        return ResponseEntity.status(503).body(new ApiError(code.code(),code.defaultMessage(),TraceContext.current(),List.of()));
    }
}
