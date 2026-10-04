package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardShareVariableScope;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 匿名数据同一只读事务内的已复验观察，不充当跨请求缓存或App身份。
 * @param principal 冻结能力身份
 * @param version 完整已复算精确Schema
 * @param scopes 冻结变量候选及精确模型
 * @param databaseNow 同次复验的数据库权威时间
 */
public record DashboardShareReadContext(DashboardSharePrincipal principal, DashboardShareSchema version,
        List<DashboardShareVariableScope> scopes, Instant databaseNow) {
    /** 防止调用方在计划校验和数据读取之间改写候选集合。 */
    public DashboardShareReadContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(version, "version");
        scopes = List.copyOf(scopes);
        Objects.requireNonNull(databaseNow, "databaseNow");
    }
}
