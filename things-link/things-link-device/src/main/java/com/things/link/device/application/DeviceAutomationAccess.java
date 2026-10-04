package com.things.link.device.application;

import com.things.link.device.domain.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 项目许可之后保护自动化目标身份；不要求设备当前在线，不暴露设备内部实体。 */
@Service
public class DeviceAutomationAccess {
    private final DeviceRepository devices;
    public DeviceAutomationAccess(DeviceRepository devices){this.devices=devices;}
    @Transactional(propagation=Propagation.MANDATORY)
    public State lock(UUID tenant,UUID project,UUID device){
        if(tenant==null||project==null||device==null)throw new IllegalArgumentException("目标范围缺失");
        return devices.lockAutomationIdentity(tenant,project,device).map(live->live?State.AVAILABLE:State.DELETED).orElse(State.MISSING);
    }
    public enum State { AVAILABLE, DELETED, MISSING }
}
