package com.things.link.integration.application;
import com.things.link.device.application.RuntimeDeviceQuery;
import java.util.List;
/** 不可变票据范围；协议与事件集合闭集。 */
public record RealtimeTicketRequest(Protocol protocol,List<String> eventTypes,List<RuntimeDeviceQuery> devices) {
    public enum Protocol { MQTT, WS }
    public RealtimeTicketRequest { eventTypes=List.copyOf(eventTypes);devices=List.copyOf(devices); }
    public int propertyCount(){return devices.stream().mapToInt(d->d.propertyKeys().size()).sum();}
}
