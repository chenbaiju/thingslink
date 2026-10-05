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
@io.swagger.v3.oas.annotations.tags.Tag(name = "开放历史与告警", description = "受 API Key 范围限制的版本化读取")
@RestController @RequestMapping("/api/open/v1") @SecurityRequirement(name="openApiKey")
public class OpenHistoryAlarmController {
    private final OpenHistoryAlarmService service;private final ObjectMapper json;
    public OpenHistoryAlarmController(OpenHistoryAlarmService service,ObjectMapper json){this.service=service;this.json=json;}
    /**
     * API Key有界版本化历史。
     * 窗口求交、最多2000点、sampleCount为十进制字符串，value沿既有double
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param deviceId 目标设备标识
     * @param expectedModelVersionId 调用方期望的不可变物模型版本
     * @param propertyKey 目标属性键
     * @param from 查询时间区间起点
     * @param to 查询时间区间终点
     * @param granularity 历史数据时间粒度
     * @param aggregation 历史数据聚合方式
     * @return 当前接口的操作结果，响应结构见 {@code PublicPropertyHistoryService.Result}
     */
    @GetMapping("/devices/{deviceId}/history")
    @Operation(operationId="getOpenPropertyHistory",summary="API Key有界版本化历史",description="窗口求交、最多2000点、sampleCount为十进制字符串，value沿既有double")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="完整有界历史",content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicPropertyHistoryService.Result.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="80002未启用或50048历史窗口不可用",content=@Content(mediaType="application/json",schema=@Schema(implementation=com.things.link.shared.error.ApiError.class)))
    public PublicPropertyHistoryService.Result history(Principal principal,HttpServletRequest request,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable String deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的不可变物模型版本") @RequestParam String expectedModelVersionId,@io.swagger.v3.oas.annotations.Parameter(description = "目标属性键") @RequestParam String propertyKey,@io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间起点") @RequestParam String from,@io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间终点") @RequestParam String to,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(allowableValues={"RAW","ONE_MINUTE","ONE_HOUR","ONE_DAY"}), description = "历史数据时间粒度") @RequestParam String granularity,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(allowableValues={"AVG","MIN","MAX","SUM","COUNT"}), description = "历史数据聚合方式") @RequestParam String aggregation){
        query(request,Set.of("expectedModelVersionId","propertyKey","from","to","granularity","aggregation"));
        return bounded(service.history(identity(principal),uuid(deviceId),uuid(expectedModelVersionId),propertyKey,time(from),time(to),granularity,aggregation),json);
    }
    /**
     * API Key设备告警只读查询。
     *
     * @param principal 认证框架提供的当前调用主体
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @PostMapping(value="/alarms/query",consumes="application/json")
    @Operation(operationId="queryOpenAlarms",summary="API Key设备告警只读查询",requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=OpenAlarmQueryRequest.class))), description = "API Key设备告警只读查询。")
    public CursorPage<AlarmDeviceQueryItem> alarms(Principal principal,HttpServletRequest request)throws IOException{
        query(request,Set.of());return bounded(service.alarms(identity(principal),body(request)),json);
    }
    private static Instant time(String text){try{Instant value=Instant.parse(text);int year=value.atOffset(ZoneOffset.UTC).getYear();if(year<1||year>9999)throw invalid();return value;}catch(DateTimeException e){throw invalid();}}
}
