package com.things.link.alarm.application;
import java.util.List;
import java.util.UUID;
/** 与HTTP DTO隔离的严格解析结果；同一内核服务Console和公开只读查询。 */
public record AlarmDeviceQueryInput(List<DeviceRequest> devices,List<String> conditionStates,List<String> ackStates,List<String> severities,String cursor,Integer limit){
    public AlarmDeviceQueryInput{devices=List.copyOf(devices);conditionStates=List.copyOf(conditionStates);ackStates=List.copyOf(ackStates);severities=List.copyOf(severities);}
    public record DeviceRequest(UUID deviceId,UUID expectedModelVersionId){}
}
