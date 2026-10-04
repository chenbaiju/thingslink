package com.things.link.iam.infrastructure.observability;

import com.things.link.iam.application.SystemHealthReader;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 从 Actuator 健康贡献者注册表读取实时状态的适配器。
 *
 * <p>白名单同时解决两个问题：其一，页面字段稳定，不会因引入一个内部探针突然改变；其二，
 * 绝不返回健康详情，避免把连接地址、驱动信息和异常栈暴露给普通项目成员。</p>
 */
@Component
public class ActuatorSystemHealthReader implements SystemHealthReader {

    /** Actuator 名称到控制台稳定名称的白名单。 */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "db", "PostgreSQL / TimescaleDB",
            "redis", "Redis",
            "diskSpace", "应用节点存储",
            "mail", "邮件通知");

    /** 运行时健康贡献者注册表。 */
    private final HealthContributorRegistry registry;

    /**
     * @param registry Actuator 已注册健康贡献者
     */
    public ActuatorSystemHealthReader(HealthContributorRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行白名单内的真实健康探针并脱敏。
     *
     * @return 按机器标识排序的状态列表
     */
    @Override
    public List<DependencyHealth> read() {
        List<DependencyHealth> result = new ArrayList<>();
        DISPLAY_NAMES.forEach((code, name) -> {
            HealthContributor contributor = registry.getContributor(code);
            if (contributor != null) {
                result.add(new DependencyHealth(code, name, statusOf(contributor)));
            }
        });
        return result.stream().sorted(Comparator.comparing(DependencyHealth::code)).toList();
    }

    /**
     * 执行一个简单或复合探针；探针抛错等价于依赖不可用，不能让状态接口自身返回 500。
     *
     * @param contributor Actuator 健康贡献者
     * @return 归一化状态
     */
    private String statusOf(HealthContributor contributor) {
        try {
            if (contributor instanceof HealthIndicator indicator) {
                return normalize(indicator.health(false).getStatus().getCode());
            }
            if (contributor instanceof CompositeHealthContributor composite) {
                List<String> statuses = composite.stream()
                        .map(entry -> statusOf(entry.contributor()))
                        .toList();
                if (statuses.stream().anyMatch("DOWN"::equals)) {
                    return "DOWN";
                }
                return statuses.isEmpty() || statuses.stream().anyMatch(status -> !"UP".equals(status))
                        ? "UNKNOWN" : "UP";
            }
            return "UNKNOWN";
        } catch (RuntimeException ignored) {
            // 健康探针的异常就是故障事实；只返回 DOWN，异常详情继续留在服务端日志与 Actuator 内部。
            return "DOWN";
        }
    }

    /**
     * 只允许前端已经定义语义的状态值，未知自定义状态统一收敛为 UNKNOWN。
     *
     * @param status Actuator 状态码
     * @return {@code UP}、{@code DOWN}、{@code OUT_OF_SERVICE} 或 {@code UNKNOWN}
     */
    private String normalize(String status) {
        return switch (status) {
            case "UP", "DOWN", "OUT_OF_SERVICE" -> status;
            default -> "UNKNOWN";
        };
    }
}
