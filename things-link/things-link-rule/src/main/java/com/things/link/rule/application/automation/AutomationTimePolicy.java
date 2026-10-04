package com.things.link.rule.application.automation;

import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** ADR0158：版本内显式时间和输入；从不读取设备影子或JVM当前时钟。 */
@Component
public final class AutomationTimePolicy {
    private final ObjectMapper json;
    public AutomationTimePolicy(ObjectMapper json){this.json=json;}
    public JsonNode normalize(String type,JsonNode config,String projectTimezone){
        try {
            if(type==null||config==null||!config.isObject())throw new IllegalArgumentException();
            UUID device=UUID.fromString(text(config,"deviceId"));
            Set<String> allowed=switch(type){
                case "PROPERTY_REPORTED" -> Set.of("deviceId");
                case "ONE_SHOT" -> Set.of("deviceId","runAt","payload");
                case "CRON" -> Set.of("deviceId","cronExpression","timezone","payload");
                default -> throw new IllegalArgumentException();
            };
            if(!allowed.containsAll(config.propertyNames()))throw new IllegalArgumentException();
            var result=json.createObjectNode().put("deviceId",device.toString());
            if(type.equals("PROPERTY_REPORTED"))return result;
            JsonNode payload=config.has("payload")?config.get("payload"):json.createObjectNode();
            if(payload==null||!payload.isObject()||json.writeValueAsBytes(payload).length>16384)throw new IllegalArgumentException();
            result.set("payload",payload.deepCopy());
            if(type.equals("ONE_SHOT"))result.put("runAt",Instant.parse(text(config,"runAt")).truncatedTo(ChronoUnit.MICROS).toString());
            else{
                String zone=config.has("timezone")?text(config,"timezone"):projectTimezone;
                if(!ZoneId.getAvailableZoneIds().contains(zone))throw new IllegalArgumentException();
                String expression=text(config,"cronExpression").strip().replaceAll("\\s+"," ");
                cron(expression);result.put("cronExpression",expression).put("timezone",zone);
            }
            return result;
        }catch(IllegalArgumentException|DateTimeException invalid){throw new BusinessException(RuleErrorCode.AUTOMATION_TRIGGER_INVALID);}
    }
    public Instant next(JsonNode config,Instant base){
        var value=cron(text(config,"cronExpression")).next(base.atZone(ZoneId.of(text(config,"timezone"))));
        return value==null?null:value.toInstant();
    }
    public Instant activate(String type,JsonNode config,Instant now,Instant floor,boolean consumed){
        if(type.equals("ONE_SHOT")){
            Instant runAt=Instant.parse(text(config,"runAt"));
            if(consumed||!runAt.isAfter(now))throw new BusinessException(RuleErrorCode.AUTOMATION_STATE_CONFLICT);
            return runAt;
        }
        Instant candidate=next(config,now);
        if(floor!=null&&(candidate==null||candidate.isBefore(floor))){
            // 下界可能为耗尽后的一微秒；从其前一纳秒计算，既保留合法点又不制造非法cron点。
            candidate=next(config,floor.minusNanos(1));
        }
        if(candidate==null)throw new BusinessException(RuleErrorCode.AUTOMATION_TRIGGER_INVALID);
        return candidate;
    }
    private static CronExpression cron(String expression){
        String[] fields=expression.split(" ");
        if(fields.length!=6||expression.startsWith("@")||expression.length()>256)throw new IllegalArgumentException();
        CronExpression parsed=CronExpression.parse(expression);
        // 秒字段每分钟必须至多一个发生点；仍接受解析器支持的等价单值范围/步长。
        CronExpression seconds=CronExpression.parse(fields[0]+" * * * * *");
        ZonedDateTime start=Instant.parse("2026-01-01T00:00:00Z").atZone(ZoneOffset.UTC).minusNanos(1);
        var first=seconds.next(start);var second=first==null?null:seconds.next(first);
        if(first==null||second==null||Duration.between(first,second).compareTo(Duration.ofMinutes(1))<0)throw new IllegalArgumentException();
        return parsed;
    }
    private static String text(JsonNode config,String field){if(!config.path(field).isString()||config.path(field).asString().isBlank())throw new IllegalArgumentException();return config.path(field).asString();}
}
