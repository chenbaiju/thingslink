package com.things.link.integration.api;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.*;
import com.things.link.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import static com.things.link.integration.api.OpenApiRequestSupport.*;
/** 三种认证链仅提取各自可信身份；凭据不能通过请求体改变。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "实时连接票据", description = "按原身份签发限定协议和设备范围的短期票据")
@RestController
@ApiResponse(responseCode="400",description="10001 范围或参数无效",content=@Content(schema=@Schema(implementation=ApiError.class)))
@ApiResponse(responseCode="401",description="80006 原身份或票据失效",content=@Content(schema=@Schema(implementation=ApiError.class)))
@ApiResponse(responseCode="409",description="10014 原响应不可重放",content=@Content(schema=@Schema(implementation=ApiError.class)))
@ApiResponse(responseCode="429",description="80005 共享连接或属性预算已满",content=@Content(schema=@Schema(implementation=ApiError.class)))
@ApiResponse(responseCode="503",description="80002 未启用或80007 租约不可用",content=@Content(schema=@Schema(implementation=ApiError.class)))
@ApiResponse(responseCode="201",description="首次短期票据，协议资格独立",content=@Content(schema=@Schema(implementation=RealtimeTicketService.Issued.class)))
public class RealtimeTicketController {
    private final RealtimeTicketService tickets;private final ProjectService projects;
    private final RealtimeTicketParser parser=new RealtimeTicketParser();
    public RealtimeTicketController(RealtimeTicketService tickets,ProjectService projects){this.tickets=tickets;this.projects=projects;}
    /**
     * Key换取限定范围的短期实时票据。
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<RealtimeTicketService.Issued>}
     */
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping(value="/api/open/v1/realtime/tickets",consumes="application/json",produces="application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="openApiKey")
    @Operation(operationId="issueOpenRealtimeTicket",summary="Key换取限定范围的短期实时票据",requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TicketRequestSchema.class))), description = "Key换取限定范围的短期实时票据。")
    public ResponseEntity<RealtimeTicketService.Issued> open(Principal principal,HttpServletRequest request)throws IOException {
        return issue(RealtimeIdentity.fromKey(identity(principal)),request);
    }
    /**
     * Console当前项目短期实时票据。
     *
     * @param projectId 接口指定的项目标识
     * @param jwt 认证框架已解析的应用访问令牌
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<RealtimeTicketService.Issued>}
     */
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="consoleAccessBearer")
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping(value="/api/v1/projects/{projectId}/realtime-tickets",consumes="application/json",produces="application/json")
    @Operation(operationId="issueConsoleRealtimeTicket",summary="Console当前项目短期实时票据",requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TicketRequestSchema.class))), description = "Console当前项目短期实时票据。")
    public ResponseEntity<RealtimeTicketService.Issued> console(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        rejectKey(request);var scope=TenantContext.current().orElseThrow(RealtimeTicketController::invalid);
        if(jwt==null||!projectId.equals(scope.projectId()))throw invalid();
        UUID owner=projects.requireProjectTenant(projectId);
        return issue(new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,owner,projectId,generation(jwt),scope.accountId(),scope.accountId(),null,jwt.getExpiresAt()),request);
    }
    /**
     * App当前绑定设备的短期实时票据。
     *
     * @param jwt 认证框架已解析的应用访问令牌
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<RealtimeTicketService.Issued>}
     */
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="appAccessBearer")
    @io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(type="string",maxLength=128))
    @PostMapping(value="/api/v1/app/realtime-tickets",consumes="application/json",produces="application/json")
    @Operation(operationId="issueAppRealtimeTicket",summary="App当前绑定设备的短期实时票据",requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TicketRequestSchema.class))), description = "App当前绑定设备的短期实时票据。")
    public ResponseEntity<RealtimeTicketService.Issued> app(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        rejectKey(request);if(jwt==null)throw invalid();
        return issue(new RealtimeIdentity(RealtimeIdentity.Kind.APP,uuid(jwt.getClaimAsString("tid")),uuid(jwt.getClaimAsString("pid")),generation(jwt),uuid(jwt.getSubject()),null,null,jwt.getExpiresAt()),request);
    }
    private ResponseEntity<RealtimeTicketService.Issued> issue(RealtimeIdentity identity,HttpServletRequest request)throws IOException {
        query(request,Set.of());
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||keys.getFirst().isBlank()||keys.getFirst().length()>128)throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(tickets.issue(identity,parser.parse(body(request)),request.getRemoteAddr()));
    }
    private static void rejectKey(HttpServletRequest request){if(request.getHeader("X-Api-Key")!=null)throw invalid();}
    private static long generation(Jwt jwt){Object value=jwt.getClaim("pgv");if(value==null)return 0;
        if(!(value instanceof Number))throw invalid();try{long generation=new java.math.BigDecimal(value.toString()).longValueExact();if(generation<0)throw invalid();return generation;}catch(ArithmeticException|NumberFormatException e){throw invalid();}}
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.REALTIME_TICKET_INVALID);}
    @Schema(name="PublicRealtimeTicketRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record TicketRequestSchema(@jakarta.validation.constraints.NotNull RealtimeTicketRequest.Protocol protocol,
        @jakarta.validation.constraints.Size(min=1,max=1) @jakarta.validation.constraints.NotNull
        @ArraySchema(minItems=1,maxItems=1,schema=@Schema(allowableValues={"device.property.report"})) List<String> eventTypes,
        @jakarta.validation.constraints.Size(min=1,max=20) @jakarta.validation.constraints.NotNull List<OpenDeviceController.DeviceRequest> devices) {}
}
