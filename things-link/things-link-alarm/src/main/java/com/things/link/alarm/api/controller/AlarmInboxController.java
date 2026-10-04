package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.request.AlarmInboxReadRequest;
import com.things.link.alarm.api.dto.response.AlarmInboxCountResponse;
import com.things.link.alarm.api.dto.response.AlarmInboxPageResponse;
import com.things.link.alarm.api.dto.response.AlarmInboxReadResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.application.AlarmInboxService;
import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.support.trace.TraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/** ADR0093：Console当前项目个人通知，不能复用为App全项目读取入口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/alarm-notifications")
@Tag(name = "站内告警通知", description = "最近三十天ACTIVATED事件与当前账号显式已读回执")
public class AlarmInboxController {
    /** 个人阅读用例，保留应用层二次授权。 */
    private final AlarmInboxService service;
    /** HTTP第一层项目成员授权。 */
    private final AlarmApiAuthorization authorization;

    /** @param service 个人阅读用例 @param authorization HTTP项目成员守卫 */
    public AlarmInboxController(AlarmInboxService service, AlarmApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /** @param projectId 当前项目 @param cursor 可空位置 @param limit 每页1至100条 @return 固定窗口全部通知页 */
    @GetMapping
    @Operation(summary = "分页查询个人站内告警通知")
    public AlarmInboxPageResponse page(@PathVariable UUID projectId, @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return AlarmInboxPageResponse.from(service.page(projectId, cursor, limit));
    }

    /** @param projectId 当前项目 @return 最多探测100条的个人未读摘要 */
    @GetMapping("/unread-count")
    @Operation(summary = "查询个人站内告警未读摘要")
    public AlarmInboxCountResponse unreadCount(@PathVariable UUID projectId) {
        authorization.requireRead(projectId);
        return new AlarmInboxCountResponse(service.unreadCount(projectId));
    }

    /** @param projectId 当前项目 @param request 明确展示的事件ID @return 新增个人回执数，不改变共享ACK */
    @PostMapping("/read")
    @Operation(summary = "原子标记明确的站内告警事件已读")
    public AlarmInboxReadResponse markRead(@PathVariable UUID projectId, @RequestBody AlarmInboxReadRequest request) {
        authorization.requireRead(projectId);
        return new AlarmInboxReadResponse(service.markRead(projectId, request == null ? null : request.eventIds()));
    }

    /** @param exception HTTP绑定错误 @return ADR0093专属参数错误，不暴露解析器内部消息 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformedInput(Exception exception) {
        AlarmErrorCode code = AlarmErrorCode.INBOX_INVALID;
        return ResponseEntity.status(code.httpStatus()).body(new ApiError(code.code(), code.defaultMessage(),
                TraceContext.resolve(TraceContext.current()), List.of()));
    }
}
