package com.things.link.integration.api;
import com.things.link.integration.application.OpenDeviceReadService;
import com.things.link.device.application.PublicDeviceReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.*;
import java.io.IOException;
import static com.things.link.integration.api.OpenApiRequestSupport.*;

/** ADR0173设备允许集，项目归属仅来自Key。 */
@RestController
@RequestMapping("/api/open/v1")
@Tag(name="公开设备读取")
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="openApiKey",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
    in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Api-Key")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="openApiKey")
public class OpenDeviceController {
    private final OpenDeviceReadService service;
    private final ObjectMapper json;
    public OpenDeviceController(OpenDeviceReadService service,ObjectMapper json){this.service=service;this.json=json;}
    @GetMapping("/devices") @Operation(operationId="listOpenDevices",summary="API Key设备目录")
    public PublicDeviceReadService.Page catalog(Principal principal,HttpServletRequest request,
            @RequestParam(required=false) String modelVersionId,@RequestParam(required=false) String cursor,@RequestParam(required=false) String limit){
        query(request,Set.of("modelVersionId","cursor","limit"));
        return bounded(service.catalog(identity(principal),modelVersionId==null?null:uuid(modelVersionId),cursor,limit(limit)),json);
    }
    @GetMapping("/devices/{deviceId}") @Operation(operationId="getOpenDevice",summary="API Key设备安全元数据")
    public PublicDeviceReadService.Detail detail(Principal principal,HttpServletRequest request,@PathVariable String deviceId){
        query(request,Set.of());return bounded(service.detail(identity(principal),uuid(deviceId)),json);
    }
    @GetMapping("/models/{versionId}") @Operation(operationId="getOpenModel",summary="API Key完整不可变模型")
    public PublicDeviceReadService.Model model(Principal principal,HttpServletRequest request,@PathVariable String versionId){
        query(request,Set.of());return bounded(service.model(identity(principal),uuid(versionId)),json);
    }
    @PostMapping(value="/devices/current-values/query",consumes="application/json")
    @Operation(operationId="queryOpenCurrentValues",summary="API Key精确模型当前值只读查询",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
            content=@Content(schema=@Schema(implementation=CurrentRequest.class))))
    public OpenCurrentValuesResponse current(Principal principal,HttpServletRequest request)throws IOException{
        query(request,Set.of());return bounded(OpenCurrentValuesResponse.from(service.current(identity(principal),body(request))),json);
    }
    @Schema(name="OpenCurrentRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record CurrentRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min=1,max=20) List<DeviceRequest> devices){}
    @Schema(name="OpenCurrentDeviceRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record DeviceRequest(@jakarta.validation.constraints.NotNull UUID deviceId,@jakarta.validation.constraints.NotNull UUID expectedModelVersionId,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min=1,max=50) List<String> propertyKeys){}
}
