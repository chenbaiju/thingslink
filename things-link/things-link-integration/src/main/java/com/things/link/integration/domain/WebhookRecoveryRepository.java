package com.things.link.integration.domain;
import java.util.*;
/** 人工恢复操作的持久回执端口，不负责网络重试或重置订阅版本。 */
public interface WebhookRecoveryRepository {
    Optional<WebhookRecoveryOperation> find(UUID operation);
    void insert(WebhookRecoveryOperation operation);
}
