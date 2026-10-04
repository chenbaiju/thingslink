package com.things.link.runtime;

import java.io.IOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;

/**
 * 将 bootstrap 同源配置作为外部应用或设备装配进程的最低优先级默认值，允许各自配置覆盖。
 * 该资源由运行时 JAR 构建从 bootstrap 原文件复制，不能手工维护第二份默认配置。
 */
public final class ThingsLinkDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        // ConfigData 与应用配置先解析，生产启动守卫最后检查最终生效值。
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean embedded = Boolean.parseBoolean(environment.getProperty("things-link.runtime.embedded", "false"));
        boolean accessDefaults = Boolean.parseBoolean(environment.getProperty("things-link.runtime.defaults", "false"));
        if (!embedded && !accessDefaults) {
            return;
        }
        try {
            addDefaults(environment, "META-INF/things-link/application.yml", "thingsLinkDefaults");
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                addDefaults(environment, "META-INF/things-link/application-prod.yml", "thingsLinkProdDefaults");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ThingsLink runtime configuration is unavailable", exception);
        }
    }

    private static void addDefaults(ConfigurableEnvironment environment, String path, String name)
            throws IOException {
        var resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("ThingsLink runtime configuration is unavailable: " + path);
        }
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(name, resource)) {
            environment.getPropertySources().addLast(source);
        }
    }
}
