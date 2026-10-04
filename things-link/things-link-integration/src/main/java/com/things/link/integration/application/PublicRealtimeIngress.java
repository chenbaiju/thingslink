package com.things.link.integration.application;
import com.things.link.shared.message.DeviceRealtimeUpdate;
/** 单Kafka消费组的公开持久接收端口；不代表设备源事务已可靠发布。 */
public interface PublicRealtimeIngress {void accept(DeviceRealtimeUpdate update);}
