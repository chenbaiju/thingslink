package com.things.link.integration.application;
import com.things.link.integration.domain.RealtimeTicketRepository;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
/** 确定失权后独立提交关闭；基础设施异常不伪装为已关闭。 */
@Service
public class RealtimeTicketMaintenanceService {
    private final RealtimeTicketService tickets;
    public RealtimeTicketMaintenanceService(RealtimeTicketService tickets){this.tickets=tickets;}
    public void maintain(RealtimeTicketRepository.Candidate candidate){
        try{tickets.validateMaintenance(candidate);}catch(BusinessException rejected){tickets.closeRejected(candidate);}
    }
}
