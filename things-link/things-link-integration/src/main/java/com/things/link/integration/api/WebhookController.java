package com.things.link.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.*;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** ADR0211：Console管理适配，写入保留原事务/操作身份，秘密不参与恢复。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/webhooks")
@Tag(name="公开Webhook管理")
public class WebhookController {
    private final WebhookSubscriptionService subscriptions; private final WebhookRecoveryService recovery;
    private final WebhookQueryService queries; private final ProjectService projects;
    private final ObjectMapper json; private final Validator validator; private final boolean enabled;
    public WebhookController(WebhookSubscriptionService subscriptions,WebhookRecoveryService recovery,WebhookQueryService queries,
            ProjectService projects,ObjectMapper json,Validator validator,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled) {
        this.subscriptions=subscriptions;this.recovery=recovery;this.queries=queries;this.projects=projects;this.json=json;this.validator=validator;this.enabled=enabled;
    }
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping @Operation(operationId="createProjectWebhook",summary="创建Webhook并首次展示签名秘密")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookCreate.class)))
    public ResponseEntity<WebhookIssued> create(@PathVariable UUID projectId,@RequestBody JsonNode body,HttpServletRequest http) {
        var actor=identity(projectId,http);var input=parse(body,WebhookCreate.class,http);var result=subscriptions.create(actor.tenant(),projectId,actor.actor(),input.operationId(),input.spec());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(WebhookIssued.from(result));
    }
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PutMapping("/{subscriptionId}") @Operation(operationId="updateProjectWebhook",summary="更新Webhook冻结修订，换目标时首次展示新秘密")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookUpdate.class)))
    public ResponseEntity<WebhookIssued> update(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,@RequestBody JsonNode body,HttpServletRequest http) {
        var actor=identity(projectId,http);long revision=revision(body);var input=parse(body,WebhookUpdate.class,http);
        return noStore(WebhookIssued.from(subscriptions.change(actor.tenant(),projectId,actor.actor(),input.operationId(),subscriptionId,revision,"UPDATE",input.spec())));
    }
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping("/{subscriptionId}/pause") @Operation(operationId="pauseProjectWebhook",summary="暂停Webhook，禁止旧修订发送")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookChange.class)))
    public ResponseEntity<WebhookIssued> pause(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,@RequestBody JsonNode body,HttpServletRequest http){return change(projectId,subscriptionId,body,http,"PAUSE");}
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping("/{subscriptionId}/resume") @Operation(operationId="resumeProjectWebhook",summary="恢复Webhook，仅接收之后新事件")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookChange.class)))
    public ResponseEntity<WebhookIssued> resume(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,@RequestBody JsonNode body,HttpServletRequest http){return change(projectId,subscriptionId,body,http,"RESUME");}
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping("/{subscriptionId}/revoke") @Operation(operationId="revokeProjectWebhook",summary="永久撤销Webhook")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookChange.class)))
    public ResponseEntity<WebhookIssued> revoke(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,@RequestBody JsonNode body,HttpServletRequest http){return change(projectId,subscriptionId,body,http,"REVOKE");}
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping("/{subscriptionId}/rotate") @Operation(operationId="rotateProjectWebhook",summary="轮换Webhook签名秘密，仅首次展示")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookChange.class)))
    public ResponseEntity<WebhookIssued> rotate(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,@RequestBody JsonNode body,HttpServletRequest http){return change(projectId,subscriptionId,body,http,"ROTATE");}
    private ResponseEntity<WebhookIssued> change(UUID project,UUID id,JsonNode body,HttpServletRequest http,String kind) {
        var actor=identity(project,http);long revision=revision(body);var input=parse(body,WebhookChange.class,http);
        return noStore(WebhookIssued.from(subscriptions.change(actor.tenant(),project,actor.actor(),input.operationId(),id,revision,kind,null)));
    }
    @GetMapping @Operation(operationId="listProjectWebhooks",summary="分页查询当前Webhook订阅")
    public ResponseEntity<CursorPage<WebhookSubscriptionView>> list(@PathVariable UUID projectId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(subscriptions.list(actor.tenant(),projectId,actor.actor(),cursor,limit).map(WebhookSubscriptionView::from));
    }
    @GetMapping("/{subscriptionId}") @Operation(operationId="getProjectWebhook",summary="查询当前订阅，不返回秘密")
    public ResponseEntity<WebhookSubscriptionView> get(@PathVariable UUID projectId,@PathVariable UUID subscriptionId,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(WebhookSubscriptionView.from(subscriptions.get(actor.tenant(),projectId,actor.actor(),subscriptionId)));
    }
    @GetMapping("/operations/{operationId}") @Operation(operationId="recoverProjectWebhookOperation",summary="恢复原订阅操作元数据，不恢复秘密")
    public ResponseEntity<WebhookOperationView> operation(@PathVariable UUID projectId,@PathVariable UUID operationId,HttpServletRequest http) {
        var actor=identity(projectId,http);var value=subscriptions.recover(actor.tenant(),projectId,actor.actor(),operationId);
        return noStore(new WebhookOperationView(value.operationId(),value.resultId(),value.kind(),value.resultRevision(),WebhookSubscriptionView.from(value.current())));
    }
    @GetMapping("/deliveries") @Operation(operationId="listProjectWebhookDeliveries",summary="按当前管理资格分页查询投递")
    public ResponseEntity<CursorPage<WebhookQueryRepository.DeliveryView>> deliveries(@PathVariable UUID projectId,@RequestParam(required=false) UUID subscriptionId,@RequestParam(required=false) String status,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(queries.deliveries(actor.tenant(),projectId,actor.actor(),subscriptionId,status,cursor,limit));
    }
    @GetMapping("/deliveries/{deliveryId}") @Operation(operationId="getProjectWebhookDelivery",summary="查询冻结目标及有界尝试，不返回正文或租约")
    public ResponseEntity<WebhookQueryRepository.DeliveryDetail> delivery(@PathVariable UUID projectId,@PathVariable UUID deliveryId,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(queries.detail(actor.tenant(),projectId,actor.actor(),deliveryId));
    }
    @GetMapping("/events") @Operation(operationId="listProjectWebhookEvents",summary="查询指定事件类型的受理与拒绝元数据")
    public ResponseEntity<CursorPage<WebhookQueryRepository.EventView>> events(@PathVariable UUID projectId,@RequestParam String eventType,@RequestParam(required=false) String result,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(queries.events(actor.tenant(),projectId,actor.actor(),eventType,result,cursor,limit));
    }
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping("/deliveries/{deliveryId}/recover") @Operation(operationId="recoverProjectWebhookDelivery",summary="按原身份恢复当前修订死信，保留次数与原文")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WebhookRecover.class)))
    public ResponseEntity<WebhookRecoveryView> recover(@PathVariable UUID projectId,@PathVariable UUID deliveryId,@RequestBody JsonNode body,HttpServletRequest http) {
        var actor=identity(projectId,http);if(!body.path("expectedRound").isIntegralNumber())throw invalid();var input=parse(body,WebhookRecover.class,http);
        return noStore(WebhookRecoveryView.from(recovery.recover(actor.tenant(),projectId,actor.actor(),input.operationId(),deliveryId,input.expectedRound())));
    }
    @GetMapping("/recovery-operations/{operationId}") @Operation(operationId="getProjectWebhookRecoveryOperation",summary="查询持久恢复收据，清理后当前明细可空")
    public ResponseEntity<WebhookRecoveryView> recoveryOperation(@PathVariable UUID projectId,@PathVariable UUID operationId,HttpServletRequest http) {
        var actor=identity(projectId,http);return noStore(WebhookRecoveryView.from(recovery.operation(actor.tenant(),projectId,actor.actor(),operationId)));
    }
    private Identity identity(UUID project,HttpServletRequest request) {
        if(request.getHeader("X-Api-Key")!=null)throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        var scope=TenantContext.current().orElseThrow(()->new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if(!project.equals(scope.projectId()))throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        if(!projects.requireRoleInProject(project).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        return new Identity(projects.requireProjectTenant(project),scope.accountId());
    }
    private <T> T parse(JsonNode body,Class<T> type,HttpServletRequest http) {
        String key=http.getHeader("Idempotency-Key");if(key==null||key.isBlank()||key.length()>128||!body.isObject())throw invalid();
        try{T value=json.treeToValue(body,type);if(!validator.validate(value).isEmpty())throw invalid();return value;}
        catch(IllegalArgumentException|tools.jackson.core.JacksonException bad){throw invalid();}
    }
    private static long revision(JsonNode body) {
        var value=body.path("expectedRevision");if(!value.isString()||!value.asText().matches("[1-9][0-9]{0,18}"))throw invalid();
        try{return Long.parseLong(value.asText());}catch(NumberFormatException bad){throw invalid();}
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    private static <T> ResponseEntity<T> noStore(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    private record Identity(UUID tenant,UUID actor) { }
    public record WebhookCreate(@NotNull UUID operationId,@NotBlank @Size(max=80) String name,@NotBlank @Size(max=2048) String targetUrl,
            @NotEmpty @Size(max=7) List<@NotBlank String> eventTypes,@NotNull @Size(max=100) List<@NotNull UUID> deviceIds) {
        WebhookSubscriptionService.Spec spec(){return new WebhookSubscriptionService.Spec(name,targetUrl,eventTypes,deviceIds);}
    }
    public record WebhookUpdate(@NotNull UUID operationId,@NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String expectedRevision,
            @NotBlank @Size(max=80) String name,@NotBlank @Size(max=2048) String targetUrl,@NotEmpty @Size(max=7) List<@NotBlank String> eventTypes,@NotNull @Size(max=100) List<@NotNull UUID> deviceIds) {
        WebhookSubscriptionService.Spec spec(){return new WebhookSubscriptionService.Spec(name,targetUrl,eventTypes,deviceIds);}
    }
    public record WebhookChange(@NotNull UUID operationId,@NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String expectedRevision) { }
    public record WebhookRecover(@NotNull UUID operationId,@Min(1) @Max(3) int expectedRound) { }
    public record WebhookSubscriptionView(UUID id,String name,String targetUrl,List<String> eventTypes,List<UUID> deviceIds,String status,String revision,String signingKeyId,Instant createdAt,Instant updatedAt) {
        static WebhookSubscriptionView from(WebhookSubscriptionService.View value){return value==null?null:new WebhookSubscriptionView(value.id(),value.name(),value.targetUrl(),value.eventTypes(),value.deviceIds(),value.status(),value.revision(),value.signingKeyId(),value.createdAt(),value.updatedAt());}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebhookIssued(WebhookSubscriptionView subscription,String signingSecret,boolean replayed) {
        static WebhookIssued from(WebhookSubscriptionService.Result value){return new WebhookIssued(WebhookSubscriptionView.from(value.subscription()),value.signingSecret(),value.replayed());}
        @Override public String toString(){return "WebhookIssued[secret=REDACTED]";}
    }
    public record WebhookOperationView(UUID operationId,UUID resultId,String kind,String resultRevision,WebhookSubscriptionView current) { }
    public record WebhookRecoveryView(UUID operationId,UUID deliveryId,int resultRound,Instant completedAt,boolean replayed,WebhookRecoveryService.Current current) {
        static WebhookRecoveryView from(WebhookRecoveryService.Result value){return new WebhookRecoveryView(value.operationId(),value.deliveryId(),value.resultRound(),value.completedAt(),value.replayed(),value.current());}
    }
}
