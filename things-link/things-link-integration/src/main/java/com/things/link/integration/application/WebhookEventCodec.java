package com.things.link.integration.application;
import com.things.link.support.webhook.PublicWebhookCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
/** Integration uses the same canonical encoder as the original source transaction. */
@Component
public class WebhookEventCodec extends PublicWebhookCodec {
    public WebhookEventCodec(ObjectMapper json){super(json);}
}
