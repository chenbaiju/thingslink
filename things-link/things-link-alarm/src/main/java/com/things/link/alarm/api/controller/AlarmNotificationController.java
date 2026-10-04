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

    /** 分页通知组。 */
    @GetMapping("/alarm-notification-groups")
    public ResponseEntity<CursorPage<AlarmNotificationGroupResponse>> groups(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(
                service.pageGroups(projectId, cursor, limit)
                        .map(AlarmNotificationGroupResponse::from));
    }

    /** 创建通知组。 */
    @PostMapping("/alarm-notification-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationGroupResponse createGroup(
            @PathVariable UUID projectId, @Valid @RequestBody SaveAlarmNotificationGroupRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationGroupResponse.from(
                service.createGroup(projectId, r.name(), r.enabled()));
    }

    /** 修改通知组。 */
    @PutMapping("/alarm-notification-groups/{groupId}")
    public AlarmNotificationGroupResponse updateGroup(
            @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @Valid @RequestBody SaveAlarmNotificationGroupRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationGroupResponse.from(
                service.updateGroup(
                        projectId, groupId, r.name(), r.enabled(), requiredVersion(r.version())));
    }

    /** 删除通知组。 */
    @DeleteMapping("/alarm-notification-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(
            @PathVariable UUID projectId, @PathVariable UUID groupId, @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteGroup(projectId, groupId, version);
    }

    /** 查询通知组收件人。 */
    @GetMapping("/alarm-notification-groups/{groupId}/recipients")
    public List<AlarmNotificationRecipientResponse> recipients(
            @PathVariable UUID projectId, @PathVariable UUID groupId) {
        authorization.requireRead(projectId);
        return service.recipients(projectId, groupId).stream()
                .map(AlarmNotificationRecipientResponse::from)
                .toList();
    }

    /** 创建收件人。 */
    @PostMapping("/alarm-notification-groups/{groupId}/recipients")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationRecipientResponse createRecipient(
            @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @Valid @RequestBody SaveAlarmNotificationRecipientRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationRecipientResponse.from(
                service.createRecipient(projectId, groupId, r.channel(), r.target(), r.enabled()));
    }

    /** 修改收件人。 */
    @PutMapping("/alarm-notification-recipients/{recipientId}")
    public AlarmNotificationRecipientResponse updateRecipient(
            @PathVariable UUID projectId,
            @PathVariable UUID recipientId,
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

    /** 删除收件人。 */
    @DeleteMapping("/alarm-notification-recipients/{recipientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecipient(
            @PathVariable UUID projectId,
            @PathVariable UUID recipientId,
            @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteRecipient(projectId, recipientId, version);
    }

    /** 分页模板。 */
    @GetMapping("/alarm-notification-templates")
    public ResponseEntity<CursorPage<AlarmNotificationTemplateResponse>> templates(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(
                service.pageTemplates(projectId, cursor, limit)
                        .map(AlarmNotificationTemplateResponse::from));
    }

    /** 创建模板。 */
    @PostMapping("/alarm-notification-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationTemplateResponse createTemplate(
            @PathVariable UUID projectId,
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

    /** 修改模板。 */
    @PutMapping("/alarm-notification-templates/{templateId}")
    public AlarmNotificationTemplateResponse updateTemplate(
            @PathVariable UUID projectId,
            @PathVariable UUID templateId,
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

    /** 删除模板。 */
    @DeleteMapping("/alarm-notification-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(
            @PathVariable UUID projectId,
            @PathVariable UUID templateId,
            @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteTemplate(projectId, templateId, version);
    }

    /** 查询规则路由。 */
    @GetMapping("/alarm-rules/{ruleId}/notification-bindings")
    public List<AlarmNotificationBindingResponse> bindings(
            @PathVariable UUID projectId, @PathVariable UUID ruleId) {
        authorization.requireRead(projectId);
        return service.bindings(projectId, ruleId).stream()
                .map(AlarmNotificationBindingResponse::from)
                .toList();
    }

    /** 创建规则路由。 */
    @PostMapping("/alarm-rules/{ruleId}/notification-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmNotificationBindingResponse createBinding(
            @PathVariable UUID projectId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody SaveAlarmNotificationBindingRequest r) {
        authorization.requireRuleManage(projectId);
        return AlarmNotificationBindingResponse.from(
                service.createBinding(
                        projectId, ruleId, r.groupId(), r.templateId(), r.channel(), r.enabled()));
    }

    /** 修改规则路由。 */
    @PutMapping("/alarm-notification-bindings/{bindingId}")
    public AlarmNotificationBindingResponse updateBinding(
            @PathVariable UUID projectId,
            @PathVariable UUID bindingId,
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

    /** 删除规则路由。 */
    @DeleteMapping("/alarm-notification-bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable UUID projectId, @PathVariable UUID bindingId, @RequestParam int version) {
        authorization.requireRuleManage(projectId);
        service.deleteBinding(projectId, bindingId, version);
    }

    /** 分页查询不含敏感载荷的投递意图。 */
    @GetMapping("/alarm-notification-deliveries")
    public ResponseEntity<CursorPage<AlarmNotificationDeliveryResponse>> deliveries(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID instanceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
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
