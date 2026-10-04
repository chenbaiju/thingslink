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
@RestController @RequestMapping("/api/open/v1/devices/{deviceId}/commands") @SecurityRequirement(name="openApiKey")
@ApiResponse(responseCode="409",description="10009候选冲突、10010处理中或10014完成墓碑；按原业务键恢复",content=@Content(mediaType="application/json",schema=@Schema(implementation=com.things.link.shared.error.ApiError.class)))
public class OpenCommandController {
    private final OpenCommandService service;private final ObjectMapper json;private final OpenCommandRequestParser parser=new OpenCommandRequestParser();
    public OpenCommandController(OpenCommandService service,ObjectMapper json){this.service=service;this.json=json;}
    @PostMapping(consumes="application/json") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="submitOpenCommand",summary="API Key受理设备命令",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Submit.class))))
    @ApiResponse(responseCode="202",description="受理成功，不代表设备执行完成",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    public PublicCommandService.Result submit(Principal principal,HttpServletRequest request,@PathVariable String deviceId,
        @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(minLength=1,maxLength=128)) @RequestHeader("Idempotency-Key") String clientKey)throws IOException{
        query(request,Set.of());if(Collections.list(request.getHeaders("Idempotency-Key")).size()!=1)throw invalid();
        var input=parser.parse(body(request));
        return bounded(service.submit(identity(principal),uuid(deviceId),clientKey,input.commandKey(),input.input()),json);
    }
    @ApiResponse(responseCode="200",description="当前命令事实",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    @GetMapping("/{commandId}") @Operation(operationId="getOpenCommand",summary="API Key查询所属命令结果")
    public PublicCommandService.Result result(Principal principal,HttpServletRequest request,@PathVariable String deviceId,@PathVariable String commandId){
        query(request,Set.of());return bounded(service.result(identity(principal),uuid(deviceId),uuid(commandId)),json);
    }
    @ApiResponse(responseCode="200",description="原命令事实",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicCommandService.Result.class)))
    @GetMapping("/by-key") @Operation(operationId="recoverOpenCommand",summary="API Key按原业务键恢复命令，不重发")
    public PublicCommandService.Result recover(Principal principal,HttpServletRequest request,@PathVariable String deviceId,@RequestParam String idempotencyKey){
        query(request,Set.of("idempotencyKey"));return bounded(service.recover(identity(principal),uuid(deviceId),idempotencyKey),json);
    }
    @Schema(name="OpenCommandSubmit",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Submit(@jakarta.validation.constraints.NotNull @Schema(pattern="^[A-Za-z0-9_-]{1,64}$") String commandKey,
        @jakarta.validation.constraints.NotNull @Schema(implementation=Object.class,type="object",additionalProperties=Schema.AdditionalPropertiesValue.TRUE) JsonNode input){}
}
