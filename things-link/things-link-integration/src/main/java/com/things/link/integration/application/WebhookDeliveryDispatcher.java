package com.things.link.integration.application;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
/** Webhook 认领及出站编排；功能开关参与认领，取得许可后交由出站服务发送。 */
@Service
public class WebhookDeliveryDispatcher {
    private final WebhookDeliveryState state;private final WebhookOutboundDelivery outbound;private final boolean enabled;
    public WebhookDeliveryDispatcher(WebhookDeliveryState state,WebhookOutboundDelivery outbound,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled){this.state=state;this.outbound=outbound;this.enabled=enabled;}
    public void dispatch(WebhookDeliveryRepository.Candidate candidate){state.claim(candidate,enabled).ifPresent(d->outbound.send(candidate,d.token()));}
}
