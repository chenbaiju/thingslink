package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
/** Webhook 有界保留清理服务；先锁定项目及推进来源时间底线，再按预算清理持久记录。 */
@Service
public class WebhookRetentionService {
    private final WebhookRetentionRepository repository;private final WebhookEventRepository events;
    private final ProjectLifecycleAccessService projects;private final TransactionLocalRlsScope rls;
    public WebhookRetentionService(WebhookRetentionRepository repository,WebhookEventRepository events,ProjectLifecycleAccessService projects,TransactionLocalRlsScope rls){this.repository=repository;this.events=events;this.projects=projects;this.rls=rls;}
    @Transactional(timeout=10) public List<WebhookRetentionRepository.Scope> candidates(){return repository.candidates();}
    @Transactional(timeout=10) public int purge(WebhookRetentionRepository.Scope scope,int limit){
        int budget=Math.min(500,Math.max(0,limit));if(budget<2)return 0;
        rls.establish(scope.tenant(),scope.project());
        if(projects.lockReadableGeneration(scope.tenant(),scope.project()).isEmpty())return 0;
        events.lockProject(scope.project());events.advanceFloor(scope.tenant(),scope.project());
        return 1+repository.purge(scope,budget-1);
    }
}
