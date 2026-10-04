package com.things.link.iam.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 汇总控制台系统状态快照。
 *
 * <p>该用例不缓存结果：健康探针本身是运行时事实，缓存会让已经故障的依赖继续显示为正常。
 * 页面主动刷新时重新读取，避免把系统状态接口变成高频轮询压力源。</p>
 */
@Service
public class SystemStatusService {

    /** 运行依赖健康读取端口。 */
    private final SystemHealthReader healthReader;

    /** UTC 观测时钟；测试可注入固定时钟，不让时间断言依赖墙上时间。 */
    private final Clock clock;

    /**
     * 生产装配入口。
     *
     * @param healthReader 真实健康读取端口
     */
    @Autowired
    public SystemStatusService(SystemHealthReader healthReader) {
        this(healthReader, Clock.systemUTC());
    }

    /**
     * 可测试构造器。
     *
     * @param healthReader 健康读取端口
     * @param clock UTC 观测时钟
     */
    SystemStatusService(SystemHealthReader healthReader, Clock clock) {
        this.healthReader = healthReader;
        this.clock = clock;
    }

    /**
     * 生成一个自洽的状态快照。
     *
     * @return 总体状态、观测时间和同一次读取产生的依赖明细
     */
    public Snapshot get() {
        List<SystemHealthReader.DependencyHealth> dependencies = healthReader.read();
        return new Snapshot(overallStatus(dependencies), Instant.now(clock), dependencies);
    }

    /**
     * 按最坏依赖状态归并总体状态。
     *
     * @param dependencies 本次真实探针结果
     * @return {@code UP}、{@code DEGRADED} 或 {@code DOWN}
     */
    private String overallStatus(List<SystemHealthReader.DependencyHealth> dependencies) {
        if (dependencies.stream().anyMatch(item -> "DOWN".equals(item.status()))) {
            return "DOWN";
        }
        if (dependencies.isEmpty()
                || dependencies.stream().anyMatch(item -> !"UP".equals(item.status()))) {
            return "DEGRADED";
        }
        return "UP";
    }

    /**
     * 控制台系统状态快照。
     *
     * @param status 总体状态
     * @param observedAt UTC 观测时间
     * @param dependencies 运行依赖明细
     */
    public record Snapshot(String status,
                           Instant observedAt,
                           List<SystemHealthReader.DependencyHealth> dependencies) {
    }
}
