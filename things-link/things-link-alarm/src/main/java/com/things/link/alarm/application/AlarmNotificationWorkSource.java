package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.support.scheduling.NotificationWorkSource;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 将告警 QUEUED 投递适配到共享通知 worker；真实发送前仍由领域 CAS 增加 attempt。 */
@Component
public class AlarmNotificationWorkSource implements NotificationWorkSource {

    /** 投递租约仓储。 */
    private final AlarmNotificationRepository repository;
    /** 事务外发送状态机。 */
    private final NotificationDeliveryExecutionService service;

    /** @param repository 告警通知仓储 @param service 发送状态机 */
    public AlarmNotificationWorkSource(
            AlarmNotificationRepository repository,
            NotificationDeliveryExecutionService service) {
        this.repository = repository;
        this.service = service;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<NotificationWork> claim(int limit, Duration leaseDuration) {
        AlarmNotificationRepository.DispatchClaim claim = repository.claimDispatches(limit, leaseDuration);
        return claim.deliveries().stream().map(candidate -> {
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    candidate.eventId(),
                    candidate.tenantId(),
                    candidate.projectId(),
                    candidate.id(),
                    candidate.instanceId(),
                    candidate.alarmEventId(),
                    candidate.attemptNo(),
                    candidate.requestedAt(),
                    candidate.traceId());
            return new NotificationWork(
                    candidate.tenantId(),
                    () -> service.deliverClaimed(request, claim.leaseToken()),
                    () -> repository.releaseDispatch(
                            candidate.projectId(), candidate.id(), claim.leaseToken(), Duration.ofSeconds(1)));
        }).toList();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String sourceName() {
        return "alarm";
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public BacklogAges observeAges() {
        AlarmNotificationRepository.DispatchAges ages = repository.dispatchAges();
        return new BacklogAges(ages.queued(), ages.sending());
    }
}
