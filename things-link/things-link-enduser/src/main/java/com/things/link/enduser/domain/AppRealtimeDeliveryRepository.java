package com.things.link.enduser.domain;
import java.util.*;
/** 本域当前身份和绑定行锁，不读取其他域事实。 */
public interface AppRealtimeDeliveryRepository {
    boolean lockIdentity(UUID tenant,UUID project,UUID user);
    int lockBindings(UUID tenant,UUID project,UUID user,List<UUID> devices);
}
