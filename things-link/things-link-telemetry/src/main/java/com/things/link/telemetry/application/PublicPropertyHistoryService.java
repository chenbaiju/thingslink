package com.things.link.telemetry.application;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.telemetry.domain.*;
import com.things.link.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

/** ADR0173版本化历史的中性跨域投影；调用方负责Key授权和同事务双轴RLS。 */
@Service
@Transactional(readOnly=true,propagation=Propagation.MANDATORY)
public class PublicPropertyHistoryService {
    private final DeviceRuntimeDataService devices;
    private final PropertyHistoryService history;
    public PublicPropertyHistoryService(DeviceRuntimeDataService devices,PropertyHistoryService history){this.devices=devices;this.history=history;}
    /** 精确模型先于保留窗口查询，不能把Key错配当作无数据成功。 */
    public Result query(UUID project,UUID device,UUID model,String property,Instant from,Instant to,String granularity,String aggregation){
        if(property==null||!property.matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
        HistoryGranularity g;HistoryAggregation a;
        try{g=HistoryGranularity.valueOf(granularity);a=HistoryAggregation.valueOf(aggregation);}catch(IllegalArgumentException|NullPointerException e){throw invalid();}
        devices.requireAllAvailable(project,List.of(new RuntimeDeviceQuery(device,model,List.of())));
        var result=history.queryVersionedTrusted(project,device,property,from,to,g,a);
        return new Result(result.requestedGranularity().name(),result.actualGranularity().name(),result.aggregation().name(),
            result.points().stream().map(v->new Point(v.ts(),v.value(),Long.toString(v.sampleCount()),v.thingModelVersionId(),v.modelVersion())).toList());
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    /** 历史值沿既有double；样本计数为十进制字符串，避免客户端整数精度丢失。 */
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenPropertyHistoryPoint")
    public record Point(Instant ts,double value,@io.swagger.v3.oas.annotations.media.Schema(pattern="^[0-9]+$") String sampleCount,UUID thingModelVersionId,String modelVersion){}
    @io.swagger.v3.oas.annotations.media.Schema(name="OpenPropertyHistoryResult")
    public record Result(String requestedGranularity,String actualGranularity,String aggregation,List<Point> points){public Result{points=List.copyOf(points);}}
}
