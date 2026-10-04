package com.things.link.runtime;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** G3-SEC-1：在数据库和邮件Bean初始化前拒绝不安全生产配置，不输出配置值。 */
public final class ProductionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 等待ConfigData与环境变量展开完成，再检查最终有效值。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String deploymentRole = environment.getProperty("things-link.deployment.role", "");
        boolean accessSource = onlySource(application, "com.things.link.access.ThingsLinkAccessApplication");
        boolean platformSource = onlySource(application, "com.things.link.ThingsLinkApplication");
        if ("device-access".equals(deploymentRole)) {
            require(accessSource, "things-link.deployment.role");
            accessProduction(environment);
            applyTrustedProxy(environment);
            return;
        }
        require(!accessSource, "things-link.deployment.role");
        require(platformSource ? "platform-api".equals(deploymentRole) : deploymentRole.isBlank(),
                "things-link.deployment.role");
        for (String key : new String[]{"spring.datasource.password", "spring.flyway.password",
                "spring.flyway.placeholders.app_role_password"}) {
            secret(environment, key, 16);
        }
        String applicationRole = Boolean.parseBoolean(environment.getProperty("things-link.runtime.embedded", "false"))
                ? required(environment, "things-link.runtime.database.app-role") : "thingslink_app";
        require(applicationRole.matches("[a-z][a-z0-9_]{0,62}"), "things-link.runtime.database.app-role");
        expect(environment, "spring.datasource.username", applicationRole);
        String owner = required(environment, "spring.flyway.user");
        require(!owner.equals(applicationRole), "spring.flyway.user");
        require(required(environment, "spring.datasource.password").equals(
                required(environment, "spring.flyway.placeholders.app_role_password")),
                "spring.flyway.placeholders.app_role_password");
        expect(environment, "spring.flyway.clean-disabled", "true");
        expect(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        expect(environment, "things-link.security.session.cookie-secure", "true");
        expect(environment, "springdoc.api-docs.enabled", "false");
        for (String key : new String[]{"things-link.security.jwt.secret", "things-link.security.app-jwt.secret",
                "things-link.security.broker-callback.secret", "things-link.notification.webhook.signing-secret"}) {
            secret(environment, key, 32);
        }
        if(Boolean.parseBoolean(environment.getProperty("things-link.integration.realtime.enabled","false"))){
            String prefix="things-link.integration.realtime.mqtt-api.";
            String address=required(environment,prefix+"base-url");
            try{var uri=java.net.URI.create(address);require(java.util.Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,prefix+"base-url");}
            catch(RuntimeException invalid){throw new IllegalStateException("Unsafe production setting: "+prefix+"base-url");}
            for(String key:new String[]{"api-key","session-api-key"}){String value=required(environment,prefix+key);require(!value.contains(":")&&!value.startsWith("tc-app-dev-"),prefix+key);}
            secret(environment,prefix+"api-secret",16);secret(environment,prefix+"session-api-secret",16);
            require(!required(environment,prefix+"api-key").equals(required(environment,prefix+"session-api-key")),prefix+"session-api-key");
        }
        required(environment, "spring.mail.host");
        integer(environment, "spring.mail.port", 65535);
        required(environment, "spring.mail.username");
        required(environment, "spring.mail.password");
        expect(environment, "spring.mail.properties.mail.smtp.auth", "true");
        boolean ssl = Boolean.parseBoolean(environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable"));
        boolean startTls = Boolean.parseBoolean(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable"))
                && Boolean.parseBoolean(environment.getProperty("spring.mail.properties.mail.smtp.starttls.required"));
        require(ssl || startTls, "spring.mail.properties.mail.smtp.ssl.enable/starttls.required");
        expect(environment, "spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
        for (String suffix : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            integer(environment, "spring.mail.properties.mail.smtp." + suffix, 30000);
        }
        applyTrustedProxy(environment);
    }

    /** 接入进程只持有应用数据库与 Broker 回调秘密，迁移、管理 JWT 和邮件归平台进程。 */
    private static void accessProduction(ConfigurableEnvironment environment) {
        secret(environment, "spring.datasource.password", 16);
        expect(environment, "spring.datasource.username", "thingslink_app");
        expect(environment, "spring.flyway.enabled", "false");
        expect(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        expect(environment, "springdoc.api-docs.enabled", "false");
        require("false".equalsIgnoreCase(environment.getProperty(
                "things-link.access.http.allow-insecure-loopback", "false")),
                "things-link.access.http.allow-insecure-loopback");
        secret(environment, "things-link.security.broker-callback.secret", 32);
    }

    private static boolean onlySource(SpringApplication application, String className) {
        return application != null && application.getAllSources().size() == 1
                && application.getAllSources().stream().anyMatch(source -> source instanceof Class<?> type
                        && type.getName().equals(className));
    }

    private static void applyTrustedProxy(ConfigurableEnvironment environment) {
        expect(environment, "server.forward-headers-strategy", "native");
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("server.tomcat.remoteip.internal-proxies", trustedProxyPattern(environment.getProperty(
                "things-link.security.trusted-proxy-addresses", "")));
        proxy.put("server.tomcat.remoteip.trusted-proxies", "");
        proxy.put("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For");
        proxy.put("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto");
        proxy.put("server.tomcat.remoteip.protocol-header-https-value", "https");
        proxy.put("server.tomcat.remoteip.host-header", "");
        proxy.put("server.tomcat.remoteip.port-header", "");
        proxy.forEach((key, value) -> require(!environment.containsProperty(key)
                || value.equals(environment.getProperty(key)), key));
        environment.getPropertySources().addFirst(new MapPropertySource("productionTrustedProxy", proxy));
    }

    /** 仅接受IP字面量；不触发DNS，也不把整段私网作为默认信任。 */
    static String trustedProxyPattern(String addresses) {
        if (addresses.isBlank()) {
            return "(?!)";
        }
        StringBuilder result = new StringBuilder();
        for (String entry : addresses.split(",", -1)) {
            String address = entry.trim();
            require(address.matches("[0-9a-fA-F:.]+") && !address.isEmpty(),
                    "things-link.security.trusted-proxy-addresses");
            if (!address.contains(":")) {
                require(address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"),
                        "things-link.security.trusted-proxy-addresses");
            }
            try {
                String normalized = InetAddress.getByName(address).getHostAddress();
                if (!result.isEmpty()) {
                    result.append('|');
                }
                result.append(Pattern.quote(address)).append('|').append(Pattern.quote(normalized));
            } catch (UnknownHostException exception) {
                throw new IllegalStateException("Unsafe production setting: things-link.security.trusted-proxy-addresses");
            }
        }
        return result.toString();
    }

    private static String required(ConfigurableEnvironment env, String key) {
        String value = env.getProperty(key, "");
        require(!value.isBlank(), key);
        return value;
    }

    private static void secret(ConfigurableEnvironment env, String key, int minimum) {
        String value = required(env, key);
        String lower = value.toLowerCase(Locale.ROOT);
        require(value.length() >= minimum && !lower.contains("dev-only")
                && !lower.contains("replace_") && !lower.equals("thingslink"), key);
    }

    private static void integer(ConfigurableEnvironment env, String key, int maximum) {
        try {
            int value = Integer.parseInt(required(env, key));
            require(value > 0 && value <= maximum, key);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Unsafe production setting: " + key);
        }
    }

    private static void expect(ConfigurableEnvironment env, String key, String expected) {
        require(expected.equalsIgnoreCase(env.getProperty(key, "")), key);
    }

    private static void require(boolean condition, String key) {
        if (!condition) {
            throw new IllegalStateException("Unsafe production setting: " + key);
        }
    }
}
