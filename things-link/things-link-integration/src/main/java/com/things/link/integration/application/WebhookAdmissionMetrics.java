package com.things.link.integration.application;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.util.*;

/** Operational results only; durable delivery facts remain the billing source. */
@Component
public class WebhookAdmissionMetrics {
    public static final String NAME="thingslink.integration.webhook.admission";
    private final Map<String,Counter> counters=new HashMap<>();
    public WebhookAdmissionMetrics(ObjectProvider<MeterRegistry> provider){
        var registry=provider.getIfAvailable(SimpleMeterRegistry::new);
        for(String result:List.of("NOT_ENABLED","OUT_OF_SCOPE","DUPLICATE","CONFLICT","STALE","OVERSIZE","BACKLOG_FULL","QUOTA_DEGRADED","ACCEPTED"))
            counters.put(result,Counter.builder(NAME).tag("result",result).register(registry));
    }
    public String afterCommit(String result){
        Counter counter=Objects.requireNonNull(counters.get(result));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){counter.increment();}});
        return result;
    }
}
