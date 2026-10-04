package com.things.link.integration.domain;
import java.util.*;
public interface WebhookRecoveryRepository {
    Optional<WebhookRecoveryOperation> find(UUID operation);
    void insert(WebhookRecoveryOperation operation);
}
