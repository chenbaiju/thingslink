package com.things.link.integration.domain;
import java.util.*;
/** Webhook 持久数据保留清理端口，按租户及项目范围执行有界删除。 */
public interface WebhookRetentionRepository {
    List<Scope> candidates();
    int purge(Scope scope,int limit);
    record Scope(UUID tenant,UUID project){}
}
