package com.things.link.alarm.api.support;
import com.things.link.alarm.api.dto.request.AlarmDeviceQueryRequest;
import com.things.link.alarm.application.AlarmDeviceQueryParser;
import org.springframework.stereotype.Component;
/** 保留Console装配与DTO，严格校验由无身份假设的应用内核执行。 */
@Component
public class AlarmDeviceQueryRequestParser {
    private final AlarmDeviceQueryParser parser=new AlarmDeviceQueryParser();
    public AlarmDeviceQueryRequest parse(byte[] source){var v=parser.parse(source);
        return new AlarmDeviceQueryRequest(v.devices().stream().map(d->new AlarmDeviceQueryRequest.DeviceRequest(d.deviceId(),d.expectedModelVersionId())).toList(),
            v.conditionStates(),v.ackStates(),v.severities(),v.cursor(),v.limit());
    }
}
