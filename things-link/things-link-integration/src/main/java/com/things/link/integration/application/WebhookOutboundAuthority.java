package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Optional;

/** ADR0182/0184：加锁顺序为项目、作者账号及成员、订阅；调用方最后获取投递锁。 */
@Service
public class WebhookOutboundAuthority {
    private final ProjectLifecycleAccessService projects;
    private final AccountDirectory accounts;
    private final ProjectActorRoleReader roles;
    private final WebhookSubscriptionRepository subscriptions;
    public WebhookOutboundAuthority(ProjectLifecycleAccessService projects,AccountDirectory accounts,
            ProjectActorRoleReader roles,WebhookSubscriptionRepository subscriptions){
        this.projects=projects;this.accounts=accounts;this.roles=roles;this.subscriptions=subscriptions;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<WebhookSubscription> lock(WebhookDeliveryRepository.Candidate candidate,WebhookDeliveryRepository.Delivery delivery){
        var generation=projects.lockReadableGeneration(candidate.tenant(),candidate.project());
        if(generation.isEmpty()||!projects.snapshot(candidate.tenant(),candidate.project()).writeAllowed())return Optional.empty();
        var snapshot=subscriptions.find(delivery.subscription());
        if(snapshot.isEmpty())return Optional.empty();
        var author=snapshot.get().authorizedBy();
        if(!accounts.lockActive(author)||roles.lockCurrent(candidate.project(),author).filter(role->role.canManageMembers()).isEmpty())return Optional.empty();
        return subscriptions.lockForDelivery(delivery.subscription()).filter(s->s.tenant().equals(candidate.tenant())
            &&s.project().equals(candidate.project())&&s.generation()==generation.getAsLong()
            &&s.status().equals("ACTIVE")&&s.revision()==delivery.revision()&&s.authorizedBy().equals(author));
    }
}
