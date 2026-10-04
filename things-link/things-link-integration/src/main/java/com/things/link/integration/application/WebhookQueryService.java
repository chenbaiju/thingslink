package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.*;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.shared.page.CursorPage;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** ADR0211：查询复验当前管理资格；游标仅定位，永不授予权限。 */
@Service
@Transactional
public class WebhookQueryService {
    private static final Set<String> STATUSES=Set.of("READY","IN_FLIGHT","SUCCEEDED","DEAD","CANCELLED");
    private static final Set<String> RESULTS=Set.of("ACCEPTED","OVERSIZE","BACKLOG_FULL","STALE","QUOTA_DEGRADED");
    private final WebhookQueryRepository repository;
    private final SignedQueryCursorCodec cursors;
    private final ProjectManagementWriteGuard management;
    private final ProjectLifecycleAccessService projects;
    private final AccountDirectory accounts;
    private final TransactionLocalRlsScope rls;
    private final boolean enabled;
    public WebhookQueryService(WebhookQueryRepository repository, SignedQueryCursorCodec cursors,
            ProjectManagementWriteGuard management, ProjectLifecycleAccessService projects, AccountDirectory accounts,
            TransactionLocalRlsScope rls, @Value("${things-link.integration.webhook.enabled:false}") boolean enabled) {
        this.repository=repository;this.cursors=cursors;this.management=management;this.projects=projects;this.accounts=accounts;this.rls=rls;this.enabled=enabled;
    }
    public CursorPage<WebhookQueryRepository.DeliveryView> deliveries(UUID tenant,UUID project,UUID actor,UUID subscription,String status,String cursor,int limit) {
        authorize(tenant,project,actor); bounded(limit); optional(status,STATUSES);
        String binding=binding(tenant,project,actor)+"|"+subscription+"|"+status;
        var anchor=cursors.decode(cursor,"WEBHOOK_DELIVERIES",binding).orElse(null);
        var values=repository.deliveries(project,subscription,status,anchor==null?null:anchor.sortTime(),anchor==null?null:anchor.sortId(),limit+1);
        return page(values,limit,"WEBHOOK_DELIVERIES",binding,WebhookQueryRepository.DeliveryView::createdAt,WebhookQueryRepository.DeliveryView::id);
    }
    public WebhookQueryRepository.DeliveryDetail detail(UUID tenant,UUID project,UUID actor,UUID id) {
        authorize(tenant,project,actor);if(id==null)throw invalid();
        return repository.detail(project,id).orElseThrow(()->new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
    public CursorPage<WebhookQueryRepository.EventView> events(UUID tenant,UUID project,UUID actor,String type,String result,String cursor,int limit) {
        authorize(tenant,project,actor);bounded(limit);
        if(type==null||!PublicWebhookEvent.EVENT_TYPES.contains(type))throw invalid();optional(result,RESULTS);
        String binding=binding(tenant,project,actor)+"|"+type+"|"+result;
        var anchor=cursors.decode(cursor,"WEBHOOK_EVENTS",binding).orElse(null);
        var values=repository.events(project,type,result,anchor==null?null:anchor.sortTime(),anchor==null?null:anchor.sortId(),limit+1);
        return page(values,limit,"WEBHOOK_EVENTS",binding,WebhookQueryRepository.EventView::acceptedAt,WebhookQueryRepository.EventView::eventId);
    }
    private <T> CursorPage<T> page(List<T> rows,int limit,String purpose,String binding,Function<T,Instant> time,Function<T,UUID> id) {
        var values=rows.stream().limit(limit).toList();
        return rows.size()>limit?CursorPage.of(values,cursors.encode(purpose,binding,time.apply(values.getLast()),id.apply(values.getLast()))):CursorPage.last(values);
    }
    private void authorize(UUID tenant,UUID project,UUID actor) {
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        if(tenant==null||project==null||actor==null)throw invalid();
        if(!management.requireMember(project,actor).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        if(!accounts.lockActive(actor)||!projects.snapshot(tenant,project).writeAllowed())throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        rls.establish(tenant,project);
    }
    private static String binding(UUID tenant,UUID project,UUID actor){return tenant+"|"+project+"|"+actor;}
    private static void bounded(int limit){if(limit<1||limit>100)throw invalid();}
    private static void optional(String value,Set<String> allowed){if(value!=null&&!allowed.contains(value))throw invalid();}
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
}
