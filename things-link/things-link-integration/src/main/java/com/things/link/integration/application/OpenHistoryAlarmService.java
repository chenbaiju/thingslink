package com.things.link.integration.application;
import com.things.link.alarm.application.*;
import com.things.link.device.application.*;
import com.things.link.telemetry.application.PublicPropertyHistoryService;
import com.things.link.shared.page.CursorPage;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Key身份下的有界历史与告警读取；无Console/App线程范围。 */
@Service
@Transactional(readOnly=true,timeout=3)
public class OpenHistoryAlarmService {
    private final TransactionLocalRlsScope rls;
    private final DeviceRuntimeDataService devices;
    private final PublicPropertyHistoryService history;
    private final AlarmDeviceQueryService alarms;
    private final SignedQueryCursorCodec cursors;
    private final AlarmDeviceQueryParser parser=new AlarmDeviceQueryParser();
    public OpenHistoryAlarmService(TransactionLocalRlsScope rls,DeviceRuntimeDataService devices,PublicPropertyHistoryService history,
            AlarmDeviceQueryService alarms,SignedQueryCursorCodec cursors){this.rls=rls;this.devices=devices;this.history=history;this.alarms=alarms;this.cursors=cursors;}
    public PublicPropertyHistoryService.Result history(ApiKeyPrincipal key,UUID device,UUID model,String property,Instant from,Instant to,String granularity,String aggregation){
        key.require("device:read",false);rls.establish(key.tenantId(),key.projectId());
        return history.query(key.projectId(),device,model,property,from,to,granularity,aggregation);
    }
    public CursorPage<AlarmDeviceQueryItem> alarms(ApiKeyPrincipal key,byte[] bytes){
        key.require("alarm:read",false);rls.establish(key.tenantId(),key.projectId());
        var q=parser.parse(bytes);int limit=q.limit()==null?20:q.limit();
        var expected=q.devices().stream().map(d->new RuntimeDeviceQuery(d.deviceId(),d.expectedModelVersionId(),List.of())).toList();
        devices.requireAllAvailable(key.projectId(),expected);
        String binding=key.tenantId()+"|"+key.projectId()+"|"+key.generation()+"|"+key.keyId()+"|"+limit+"|"
            +q.devices().stream().map(d->d.deviceId()+":"+d.expectedModelVersionId()).sorted().collect(Collectors.joining(","))
            +"|"+ordered(q.conditionStates())+"|"+ordered(q.ackStates())+"|"+ordered(q.severities())+"|updatedAt,id:DESC";
        var anchor=cursors.decode(q.cursor(),"OPEN_ALARM_QUERY",binding);
        var page=alarms.query(key.tenantId(),key.projectId(),q.devices().stream().map(AlarmDeviceQueryInput.DeviceRequest::deviceId).toList(),
            Set.copyOf(q.conditionStates()),Set.copyOf(q.ackStates()),Set.copyOf(q.severities()),
            anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null),limit);
        return page.hasMore()?CursorPage.of(page.items(),cursors.encode("OPEN_ALARM_QUERY",binding,page.nextUpdatedAt(),page.nextId())):CursorPage.last(page.items());
    }
    private static String ordered(List<String> values){return values.stream().sorted().collect(Collectors.joining(","));}
}
