package com.things.link.access;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** 在装配任何业务 Bean 前拒绝错误角色、端口和迁移所有权配置。 */
public final class AccessRoleEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        require("device-access".equals(environment.getProperty("things-link.deployment.role")),
                "things-link.deployment.role");
        require("false".equalsIgnoreCase(environment.getProperty("things-link.runtime.embedded", "false")),
                "things-link.runtime.embedded");
        require("true".equalsIgnoreCase(environment.getProperty("things-link.runtime.defaults")),
                "things-link.runtime.defaults");
        require("false".equalsIgnoreCase(environment.getProperty("spring.flyway.enabled")),
                "spring.flyway.enabled");
        int serverPort = port(environment.getProperty("server.port"), "server.port");
        int accessPort = port(environment.getProperty("things-link.access.http.port"),
                "things-link.access.http.port");
        require(serverPort == accessPort && serverPort != 8080, "things-link.access.http.port");
    }

    private static int port(String value, String key) {
        try {
            int parsed = Integer.parseInt(value);
            require(parsed > 0 && parsed <= 65535, key);
            return parsed;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalStateException("Invalid device-access setting: " + key);
        }
    }

    private static void require(boolean valid, String key) {
        if (!valid) {
            throw new IllegalStateException("Invalid device-access setting: " + key);
        }
    }
}
