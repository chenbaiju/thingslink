package com.things.link.integration.domain;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import java.util.UUID;
public interface RealtimeEventRepository {
    Admission admit(DeviceRealtimeUpdate update,String sourceHash);
    enum Admission {NEW,DUPLICATE,CONFLICT}
    int pending(UUID ticket);
    void enqueue(RealtimeTicket ticket,DeviceRealtimeUpdate update,String envelope);
}
