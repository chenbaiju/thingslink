package com.things.link.rule.application;

import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.support.scheduling.NotificationWorkSource;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 将规则 QUEUED 投递适配到告警/规则共享通知 worker。 */
@Component
public class RuleNotificationWorkSource implements NotificationWorkSource {

    /** 规则通知持久状态机。 */
    private final RuleNotificationDeliveryStore store;
    /** 事务外发送服务。 */
    private final RuleNotificationDeliveryService service;

    /** @param store 投递状态机 @param service 发送服务 */
    public RuleNotificationWorkSource(
            RuleNotificationDeliveryStore store,
            RuleNotificationDeliveryService service) {
        this.store = store;
        this.service = service;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<NotificationWork> claim(int limit, Duration leaseDuration) {
        RuleNotificationDeliveryStore.DispatchClaim claim = store.claimDispatches(limit, leaseDuration);
        return claim.deliveries().stream().map(candidate -> {
            RuleNotificationDeliveryRequest request = new RuleNotificationDeliveryRequest(
                    candidate.id(), candidate.tenantId(), candidate.projectId(),
                    candidate.ruleId(), candidate.ruleVersionId(), candidate.messageId(),
                    candidate.sceneId(), candidate.sceneVersionId(), candidate.sceneExecutionId(),
                    candidate.deviceId(), candidate.channel(), candidate.recipient(), candidate.subject(),
                    candidate.body(), candidate.traceId(), candidate.attemptNo(), candidate.enqueuedAt(),
                    candidate.automationId(), candidate.automationVersionId(), candidate.automationExecutionId());
            return new NotificationWork(
                    candidate.tenantId(),
                    () -> service.deliverClaimed(request, claim.leaseToken()),
                    () -> store.releaseDispatch(
                            candidate.projectId(), candidate.id(), claim.leaseToken(), Duration.ofSeconds(1)));
        }).toList();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String sourceName() {
        return "rule";
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public BacklogAges observeAges() {
        RuleNotificationDeliveryStore.DispatchAges ages = store.dispatchAges();
        return new BacklogAges(ages.queued(), ages.sending());
    }
}
