package com.things.link.integration.api;
import com.things.link.integration.application.*;
import com.things.link.telemetry.application.PublicCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.*;
import java.security.Principal;
import java.util.*;
import java.io.IOException;
import static com.things.link.integration.api.OpenApiRequestSupport.*;

/** 公开命令202仅代表原事务受理；完成事实通过当前Key收据恢复。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "开放命令", description = "受 API Key 范围限制的设备命令")
@RestController @RequestMapping("/api/open/v1/devices/{deviceId}/commands") @SecurityRequirement(name="openApiKey")
@ApiResponse(responseCode="409",description="10009候选冲突、10010处理中或10014完成墓碑；按原业务键恢复",content=@Content(mediaType="application/json",schema=@Schema(implementation=com.things.link.shared.error.ApiError.class)))
public class OpenCommandController {
    private final OpenCommandService service;private final ObjectMapper json;private final OpenCommandRequestParser parser=new OpenCommandRequestParser();
    public OpenCommandController(OpenCommandService service,ObjectMapper json){this.service=service;this.json=json;}
    /**
     * API Key受理设备命令。
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param deviceId 目标设备标识
     * @param clientKey 客户端提交的幂等键
     * @return 当前接口的操作结果，响应结构见 {@code PublicCommandService.Result}
     */
    @PostMapping(consumes="application/json") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="submitOpenCommand",summary="API Key受理设备命令",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Submit.class))), description = "API Key受理设备命令。")
    @ApiResponse(responseCode="202",description="受理成功，不代表设备执行完成",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    public PublicCommandService.Result submit(Principal principal,HttpServletRequest request,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable String deviceId,
        @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(minLength=1,maxLength=128), description = "客户端提交的幂等键") @RequestHeader("Idempotency-Key") String clientKey)throws IOException{
        query(request,Set.of());if(Collections.list(request.getHeaders("Idempotency-Key")).size()!=1)throw invalid();
        var input=parser.parse(body(request));
        return bounded(service.submit(identity(principal),uuid(deviceId),clientKey,input.commandKey(),input.input()),json);
    }
    /**
     * API Key查询所属命令结果。
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param deviceId 目标设备标识
     * @param commandId 设备命令标识
     * @return 当前接口的操作结果，响应结构见 {@code PublicCommandService.Result}
     */
    @ApiResponse(responseCode="200",description="当前命令事实",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    @GetMapping("/{commandId}") @Operation(operationId="getOpenCommand",summary="API Key查询所属命令结果", description = "API Key查询所属命令结果。")
    public PublicCommandService.Result result(Principal principal,HttpServletRequest request,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable String deviceId,@io.swagger.v3.oas.annotations.Parameter(description = "设备命令标识") @PathVariable String commandId){
        query(request,Set.of());return bounded(service.result(identity(principal),uuid(deviceId),uuid(commandId)),json);
    }
    /**
     * API Key按原业务键恢复命令，不重发。
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param deviceId 目标设备标识
     * @param idempotencyKey 本次操作的幂等键，用于识别重复提交
     * @return 当前接口的操作结果，响应结构见 {@code PublicCommandService.Result}
     */
    @ApiResponse(responseCode="200",description="原命令事实",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    @GetMapping("/by-key") @Operation(operationId="recoverOpenCommand",summary="API Key按原业务键恢复命令，不重发", description = "API Key按原业务键恢复命令，不重发。")
    public PublicCommandService.Result recover(Principal principal,HttpServletRequest request,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable String deviceId,@io.swagger.v3.oas.annotations.Parameter(description = "本次操作的幂等键，用于识别重复提交") @RequestParam String idempotencyKey){
        query(request,Set.of("idempotencyKey"));return bounded(service.recover(identity(principal),uuid(deviceId),idempotencyKey),json);
    }
    @Schema(name="OpenCommandSubmit",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Submit(@jakarta.validation.constraints.NotNull @Schema(pattern="^[A-Za-z0-9_-]{1,64}$") String commandKey,
        @jakarta.validation.constraints.NotNull @Schema(implementation=Object.class,type="object",additionalProperties=Schema.AdditionalPropertiesValue.TRUE) JsonNode input){}
}
