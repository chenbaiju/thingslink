package com.things.link.project.domain;

import java.time.Instant;

/** 内部仓储返回同语句的UTC下界及右开上界。 */
public record PlanHistoryWindow(Instant from, Instant to) { }
