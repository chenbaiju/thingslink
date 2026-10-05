package com.things.link.integration.application;
import com.things.link.support.webhook.PublicWebhookCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
/** 集成模块与原始来源事务使用同一个规范编码器。 */
@Component
public class WebhookEventCodec extends PublicWebhookCodec {
    public WebhookEventCodec(ObjectMapper json){super(json);}
}
