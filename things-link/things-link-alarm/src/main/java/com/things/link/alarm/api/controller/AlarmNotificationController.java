package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.request.SaveAlarmNotificationBindingRequest;
import com.things.link.alarm.api.dto.request.SaveAlarmNotificationGroupRequest;
import com.things.link.alarm.api.dto.request.SaveAlarmNotificationRecipientRequest;
import com.things.link.alarm.api.dto.request.SaveAlarmNotificationTemplateRequest;
import com.things.link.alarm.api.dto.response.AlarmNotificationBindingResponse;
import com.things.link.alarm.api.dto.response.AlarmNotificationDeliveryResponse;
import com.things.link.alarm.api.dto.response.AlarmNotificationGroupResponse;
import com.things.link.alarm.api.dto.response.AlarmNotificationRecipientResponse;
import com.things.link.alarm.api.dto.response.AlarmNotificationTemplateResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.application.NotificationConfigurationService;
import com.things.link.shared.page.CursorPage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 告警通知配置与投递意图 HTTP API；所有写接口均有 Controller 和服务双层项目授权。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "告警通知配置", description = "通知组、收件人、模板、规则绑定与投递记录")
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AlarmNotificationController {
    /** 通知配置用例。 */
    private final NotificationConfigurationService service;

    /** 第一层项目授权守卫。 */
    private final AlarmApiAuthorization authorization;

    /**
     * @param service 通知配置用例 @param authorization HTTP 授权守卫
     */
    public AlarmNotificationController(
            NotificationConfigurationService service, AlarmApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 分页通知组。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "分页通知组", description = "按项目读取通知组的游标页；要求项目读取权限，cursor 和 limit 沿当前分页校验。")
    @GetMapping("/alarm-notification-groups")
    public ResponseEntity<CursorPage<AlarmNotificationGroupResponse>> groups(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(
                service.pageGroups(projectId, cursor, limit)
                        .map(AlarmNotificationGroupResponse::from));
    }

    /**
     * 创建通知组。
     *
     * @param projectId 接口指定的项目标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationGroupRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationGroupResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建通知组", description = "在当前项目创建通知组；要求规则管理权限，校验名称并返回新建记录。")
    @PostMapping("/alarm-notification-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationGroupResponse createGroup(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @Valid @RequestBody SaveAlarmNotificationGroupRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationGroupResponse.from(
                service.createGroup(projectId, r.name(), r.enabled()));
    }

    /**
     * 修改通知组。
     *
     * @param projectId 接口指定的项目标识
     * @param groupId 通知组标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationGroupRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationGroupResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "修改通知组", description = "修改当前项目的通知组名称与启用状态；要求规则管理权限并携带当前 version，版本冲突拒绝更新。")
    @PutMapping("/alarm-notification-groups/{groupId}")
    public AlarmNotificationGroupResponse updateGroup(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知组标识") @PathVariable UUID groupId,
            @Valid @RequestBody SaveAlarmNotificationGroupRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationGroupResponse.from(
                service.updateGroup(
                        projectId, groupId, r.name(), r.enabled(), requiredVersion(r.version())));
    }

    /**
     * 删除通知组。
     *
     * @param projectId 接口指定的项目标识
     * @param groupId 通知组标识
     * @param version 调用方期望的资源版本，用于并发更新校验
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除通知组", description = "按项目与组标识删除通知组；要求规则管理权限，version 用于防止并发覆盖。")
    @DeleteMapping("/alarm-notification-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "通知组标识") @PathVariable UUID groupId, @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteGroup(projectId, groupId, version);
    }

    /**
     * 查询通知组收件人。
     *
     * @param projectId 接口指定的项目标识
     * @param groupId 通知组标识
     * @return 符合当前查询条件的结果列表
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "查询通知组收件人", description = "读取当前项目指定通知组的收件人列表；要求项目读取权限，组归属由服务再次核对。")
    @GetMapping("/alarm-notification-groups/{groupId}/recipients")
    public List<AlarmNotificationRecipientResponse> recipients(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "通知组标识") @PathVariable UUID groupId) {
        authorization.requireRead(projectId);
        return service.recipients(projectId, groupId).stream()
                .map(AlarmNotificationRecipientResponse::from)
                .toList();
    }

    /**
     * 创建收件人。
     *
     * @param projectId 接口指定的项目标识
     * @param groupId 通知组标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationRecipientRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationRecipientResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建收件人", description = "在当前项目通知组添加收件人及通道；要求规则管理权限，目标与通道按现有通知配置规则校验。")
    @PostMapping("/alarm-notification-groups/{groupId}/recipients")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationRecipientResponse createRecipient(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知组标识") @PathVariable UUID groupId,
            @Valid @RequestBody SaveAlarmNotificationRecipientRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationRecipientResponse.from(
                service.createRecipient(projectId, groupId, r.channel(), r.target(), r.enabled()));
    }

    /**
     * 修改收件人。
     *
     * @param projectId 接口指定的项目标识
     * @param recipientId 通知接收者标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationRecipientRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationRecipientResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "修改收件人", description = "修改当前项目通知组的收件人配置；要求规则管理权限及当前 version，不能跨项目改写。")
    @PutMapping("/alarm-notification-recipients/{recipientId}")
    public AlarmNotificationRecipientResponse updateRecipient(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知接收者标识") @PathVariable UUID recipientId,
            @Valid @RequestBody SaveAlarmNotificationRecipientRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationRecipientResponse.from(
                service.updateRecipient(
                        projectId,
                        recipientId,
                        r.channel(),
                        r.target(),
                        r.enabled(),
                        requiredVersion(r.version())));
    }

    /**
     * 删除收件人。
     *
     * @param projectId 接口指定的项目标识
     * @param recipientId 通知接收者标识
     * @param version 调用方期望的资源版本，用于并发更新校验
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除收件人", description = "删除当前项目通知组的指定收件人；要求规则管理权限并按 version 检查并发修改。")
    @DeleteMapping("/alarm-notification-recipients/{recipientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecipient(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知接收者标识") @PathVariable UUID recipientId,
            @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteRecipient(projectId, recipientId, version);
    }

    /**
     * 分页模板。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "分页模板", description = "按项目读取通知模板的游标页；要求项目读取权限，模板正文仅沿当前配置合同返回。")
    @GetMapping("/alarm-notification-templates")
    public ResponseEntity<CursorPage<AlarmNotificationTemplateResponse>> templates(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(
                service.pageTemplates(projectId, cursor, limit)
                        .map(AlarmNotificationTemplateResponse::from));
    }

    /**
     * 创建模板。
     *
     * @param projectId 接口指定的项目标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationTemplateRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationTemplateResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建模板", description = "在当前项目创建通道通知模板；要求规则管理权限，正文、变量及通道沿现有规则校验。")
    @PostMapping("/alarm-notification-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationTemplateResponse createTemplate(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @Valid @RequestBody SaveAlarmNotificationTemplateRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationTemplateResponse.from(
                service.createTemplate(
                        projectId,
                        r.name(),
                        r.channel(),
                        r.subjectTemplate(),
                        r.bodyTemplate(),
                        r.enabled()));
    }

    /**
     * 修改模板。
     *
     * @param projectId 接口指定的项目标识
     * @param templateId 通知模板标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationTemplateRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationTemplateResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "修改模板", description = "更新当前项目通知模板；要求规则管理权限及当前 version，模板内容沿现有规则校验。")
    @PutMapping("/alarm-notification-templates/{templateId}")
    public AlarmNotificationTemplateResponse updateTemplate(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知模板标识") @PathVariable UUID templateId,
            @Valid @RequestBody SaveAlarmNotificationTemplateRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationTemplateResponse.from(
                service.updateTemplate(
                        projectId,
                        templateId,
                        r.name(),
                        r.channel(),
                        r.subjectTemplate(),
                        r.bodyTemplate(),
                        r.enabled(),
                        requiredVersion(r.version())));
    }

    /**
     * 删除模板。
     *
     * @param projectId 接口指定的项目标识
     * @param templateId 通知模板标识
     * @param version 调用方期望的资源版本，用于并发更新校验
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除模板", description = "删除当前项目的通知模板；要求规则管理权限并携带 version，引用及并发约束由服务校验。")
    @DeleteMapping("/alarm-notification-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知模板标识") @PathVariable UUID templateId,
            @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteTemplate(projectId, templateId, version);
    }

    /**
     * 查询规则路由。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @return 符合当前查询条件的结果列表
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "查询规则路由", description = "读取当前项目告警规则的通知绑定列表；要求项目读取权限，规则归属由服务核对。")
    @GetMapping("/alarm-rules/{ruleId}/notification-bindings")
    public List<AlarmNotificationBindingResponse> bindings(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @PathVariable UUID ruleId) {
        authorization.requireRead(projectId);
        return service.bindings(projectId, ruleId).stream()
                .map(AlarmNotificationBindingResponse::from)
                .toList();
    }

    /**
     * 创建规则路由。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationBindingRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationBindingResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建规则路由", description = "为当前项目告警规则创建通知组与模板绑定；要求规则管理权限，所有引用须属于该项目。")
    @PostMapping("/alarm-rules/{ruleId}/notification-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationBindingResponse createBinding(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @PathVariable UUID ruleId,
            @Valid @RequestBody SaveAlarmNotificationBindingRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationBindingResponse.from(
                service.createBinding(
                        projectId, ruleId, r.groupId(), r.templateId(), r.channel(), r.enabled()));
    }

    /**
     * 修改规则路由。
     *
     * @param projectId 接口指定的项目标识
     * @param bindingId 通知绑定标识
     * @param r 本次操作的请求数据，结构见 {@code SaveAlarmNotificationBindingRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmNotificationBindingResponse}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "修改规则路由", description = "修改当前项目告警规则的通知绑定；要求规则管理权限及当前 version，引用归属由服务核对。")
    @PutMapping("/alarm-notification-bindings/{bindingId}")
    public AlarmNotificationBindingResponse updateBinding(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "通知绑定标识") @PathVariable UUID bindingId,
            @Valid @RequestBody SaveAlarmNotificationBindingRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationBindingResponse.from(
                service.updateBinding(
                        projectId,
                        bindingId,
                        r.groupId(),
                        r.templateId(),
                        r.channel(),
                        r.enabled(),
                        requiredVersion(r.version())));
    }

    /**
     * 删除规则路由。
     *
     * @param projectId 接口指定的项目标识
     * @param bindingId 通知绑定标识
     * @param version 调用方期望的资源版本，用于并发更新校验
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除规则路由", description = "删除当前项目告警规则的指定通知绑定；要求规则管理权限并按 version 防止并发覆盖。")
    @DeleteMapping("/alarm-notification-bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "通知绑定标识") @PathVariable UUID bindingId, @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteBinding(projectId, bindingId, version);
    }

    /**
     * 分页查询不含敏感载荷的投递意图。
     *
     * @param projectId 接口指定的项目标识
     * @param instanceId 告警实例标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "分页查询不含敏感载荷的投递意图", description = "按项目游标分页查询投递意图，可按告警实例筛选；要求项目读取权限，响应不包含敏感通知载荷，意图状态不等于供应商送达回执。")
    @GetMapping("/alarm-notification-deliveries")
    public ResponseEntity<CursorPage<AlarmNotificationDeliveryResponse>> deliveries(
            @io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "告警实例标识") @RequestParam(required = false) UUID instanceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(
                service.pageDeliveries(projectId, instanceId, cursor, limit)
                        .map(AlarmNotificationDeliveryResponse::from));
    }

    /**
     * @return 更新请求必须携带的乐观锁版本
     */
    private static int requiredVersion(Integer value) {
        if (value == null) throw new IllegalArgumentException("version 不能为空");
        return value;
    }
}
