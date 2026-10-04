package com.things.link.integration.api;
import com.things.link.integration.application.OpenHistoryAlarmService;
import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.telemetry.application.PublicPropertyHistoryService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.*;
import java.util.*;
import java.io.IOException;
import static com.things.link.integration.api.OpenApiRequestSupport.*;

/** ADR0173精确模型历史及只读告警路由。 */
@RestController @RequestMapping("/api/open/v1") @SecurityRequirement(name="openApiKey")
public class OpenHistoryAlarmController {
    private final OpenHistoryAlarmService service;private final ObjectMapper json;
    public OpenHistoryAlarmController(OpenHistoryAlarmService service,ObjectMapper json){this.service=service;this.json=json;}
    @GetMapping("/devices/{deviceId}/history")
    @Operation(operationId="getOpenPropertyHistory",summary="API Key有界版本化历史",description="窗口求交、最多2000点、sampleCount为十进制字符串，value沿既有double")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="完整有界历史",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicPropertyHistoryService.Result.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="80002未启用或50048历史窗口不可用",content=@Content(mediaType="application/json",schema=@Schema(implementation=com.things.link.shared.error.ApiError.class)))
    public PublicPropertyHistoryService.Result history(Principal principal,HttpServletRequest request,@PathVariable String deviceId,
            @RequestParam String expectedModelVersionId,@RequestParam String propertyKey,@RequestParam String from,@RequestParam String to,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(allowableValues={"RAW","ONE_MINUTE","ONE_HOUR","ONE_DAY"})) @RequestParam String granularity,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(allowableValues={"AVG","MIN","MAX","SUM","COUNT"})) @RequestParam String aggregation){
        query(request,Set.of("expectedModelVersionId","propertyKey","from","to","granularity","aggregation"));
        return bounded(service.history(identity(principal),uuid(deviceId),uuid(expectedModelVersionId),propertyKey,time(from),time(to),granularity,aggregation),json);
    }
    @PostMapping(value="/alarms/query",consumes="application/json")
    @Operation(operationId="queryOpenAlarms",summary="API Key设备告警只读查询",requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=OpenAlarmQueryRequest.class))))
    public CursorPage<AlarmDeviceQueryItem> alarms(Principal principal,HttpServletRequest request)throws IOException{
        query(request,Set.of());return bounded(service.alarms(identity(principal),body(request)),json);
    }
    private static Instant time(String text){try{Instant value=Instant.parse(text);int year=value.atOffset(ZoneOffset.UTC).getYear();if(year<1||year>9999)throw invalid();return value;}catch(DateTimeException e){throw invalid();}}
}
