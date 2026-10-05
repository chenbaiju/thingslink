package com.things.link.integration.domain;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import java.util.UUID;
/** 可信实时来源去重及投递入队端口；不从公开请求构造设备事实。 */
public interface RealtimeEventRepository {
    Admission admit(DeviceRealtimeUpdate update,String sourceHash);
    enum Admission {NEW,DUPLICATE,CONFLICT}
    int pending(UUID ticket);
    void enqueue(RealtimeTicket ticket,DeviceRealtimeUpdate update,String envelope);
}
