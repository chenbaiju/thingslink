package com.things.link.integration.application;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.shared.error.RealtimeAdmissionUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
/** 公开实时事件接入适配器；禁用时不准入，持久依赖故障继续向上游传播。 */
@Service
public class RealtimeEventIngress implements PublicRealtimeIngress {
    private final RealtimeEventAdmission admission;private final boolean enabled;
    public RealtimeEventIngress(RealtimeEventAdmission admission,@Value("${things-link.integration.realtime.enabled:false}") boolean enabled){this.admission=admission;this.enabled=enabled;}
    @Override public void accept(DeviceRealtimeUpdate update){if(!enabled)return;
        try{admission.accept(update);}catch(DataAccessException|TransactionException failure){throw new RealtimeAdmissionUnavailableException(failure);}
    }
}
