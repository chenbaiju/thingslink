package com.things.link.integration.domain;
import java.util.*;
public interface WebhookRetentionRepository {
    List<Scope> candidates();
    int purge(Scope scope,int limit);
    record Scope(UUID tenant,UUID project){}
}
