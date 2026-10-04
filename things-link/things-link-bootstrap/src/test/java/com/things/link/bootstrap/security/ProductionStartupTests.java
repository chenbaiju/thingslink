package com.things.link.bootstrap.security;

import com.things.link.ThingsLinkApplication;
import com.things.link.runtime.ProductionEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证最终配置拒绝、秘密不回显及真实Boot入口的早期执行。 */
class ProductionStartupTests {

    static Map<String, Object> validProperties() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.profiles.active", "prod");
        values.put("spring.datasource.username", "thingslink_app");
        values.put("spring.datasource.password", "runtime-test-secret-123456");
        values.put("spring.flyway.user", "migration_owner");
        values.put("spring.flyway.password", "owner-test-secret-123456");
        values.put("spring.flyway.placeholders.app_role_password", "runtime-test-secret-123456");
        values.put("spring.flyway.clean-disabled", "true");
        values.put("spring.jpa.hibernate.ddl-auto", "validate");
        values.put("things-link.security.session.cookie-secure", "true");
        values.put("springdoc.api-docs.enabled", "false");
        for (String key : new String[]{"things-link.security.jwt.secret", "things-link.security.app-jwt.secret",
                "things-link.security.broker-callback.secret", "things-link.notification.webhook.signing-secret"}) {
            values.put(key, "test-secret-with-more-than-thirty-two-bytes");
        }
        values.put("spring.mail.host", "smtp.example.test");
        values.put("spring.mail.port", "465");
        values.put("spring.mail.username", "sender@example.test");
        values.put("spring.mail.password", "private-mail-test-value");
        values.put("spring.mail.properties.mail.smtp.auth", "true");
        values.put("spring.mail.properties.mail.smtp.ssl.enable", "true");
        values.put("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
        for (String suffix : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            values.put("spring.mail.properties.mail.smtp." + suffix, "5000");
        }
        values.put("server.forward-headers-strategy", "native");
        return values;
    }

    private MockEnvironment environment() {
        MockEnvironment result = new MockEnvironment();
        validProperties().forEach((key, value) -> result.setProperty(key, value.toString()));
        return result;
    }

    @ParameterizedTest
    @CsvSource({
            "spring.datasource.password,thingslink", "spring.datasource.username,postgres",
            "spring.flyway.user,thingslink_app", "spring.flyway.password,thingslink",
            "spring.flyway.placeholders.app_role_password,different-long-password",
            "spring.flyway.clean-disabled,false", "spring.jpa.hibernate.ddl-auto,update",
            "things-link.security.session.cookie-secure,false", "springdoc.api-docs.enabled,true",
            "things-link.security.jwt.secret,dev-only-secret-do-not-use-in-production-32b",
            "things-link.security.app-jwt.secret,short", "things-link.security.broker-callback.secret,short",
            "things-link.notification.webhook.signing-secret,short", "spring.mail.host,''",
            "spring.mail.port,0", "spring.mail.port,65536", "spring.mail.port,not-a-number",
            "spring.mail.username,''", "spring.mail.password,''", "spring.mail.properties.mail.smtp.auth,false",
            "spring.mail.properties.mail.smtp.ssl.enable,false", "spring.mail.properties.mail.smtp.ssl.checkserveridentity,false",
            "spring.mail.properties.mail.smtp.timeout,0", "spring.mail.properties.mail.smtp.writetimeout,30001",
            "server.forward-headers-strategy,framework", "server.tomcat.remoteip.internal-proxies,.*",
            "server.tomcat.remoteip.host-header,X-Forwarded-Host", "things-link.security.trusted-proxy-addresses,10.0.0.0/8",
            "things-link.security.trusted-proxy-addresses,localhost", "things-link.security.trusted-proxy-addresses,127.1",
            "things-link.security.trusted-proxy-addresses,999.1.1.1"
    })
    void rejectsUnsafeFinalConfiguration(String key, String value) {
        MockEnvironment env = environment().withProperty(key, value);
        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsafe production setting:")
                .hasMessageNotContaining("private-mail-test-value").hasMessageNotContaining("runtime-test-secret-123456");
    }

    @Test
    void noImplicitPrivateOrLoopbackTrust() {
        MockEnvironment env = environment();
        new ProductionEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat("127.0.0.1".matches(env.getRequiredProperty("server.tomcat.remoteip.internal-proxies"))).isFalse();
        assertThat("10.0.0.1".matches(env.getRequiredProperty("server.tomcat.remoteip.internal-proxies"))).isFalse();
    }

    @Test
    void externalRuntimeUsesItsDeclaredApplicationRoleWithoutChangingOriginalGuard() {
        MockEnvironment external = environment()
                .withProperty("things-link.runtime.embedded", "true")
                .withProperty("things-link.runtime.database.app-role", "jagonzn_app")
                .withProperty("spring.datasource.username", "jagonzn_app")
                .withProperty("spring.flyway.user", "jagonzn");
        new ProductionEnvironmentPostProcessor().postProcessEnvironment(external, null);
        assertThatThrownBy(() -> new ProductionEnvironmentPostProcessor().postProcessEnvironment(
                environment().withProperty("things-link.runtime.embedded", "true")
                        .withProperty("things-link.runtime.database.app-role", "jagonzn_app")
                        .withProperty("spring.datasource.username", "jagonzn_app")
                        .withProperty("spring.flyway.user", "jagonzn_app"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.user");
    }

    @Test
    void literalIpv6AndMandatoryStartTlsAreSupported() {
        MockEnvironment env = environment().withProperty("things-link.security.trusted-proxy-addresses", "::1,192.0.2.10")
                .withProperty("spring.mail.properties.mail.smtp.ssl.enable", "false")
                .withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true");
        new ProductionEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat("0:0:0:0:0:0:0:1".matches(env.getRequiredProperty("server.tomcat.remoteip.internal-proxies"))).isTrue();
        assertThat("192.0.2.11".matches(env.getRequiredProperty("server.tomcat.remoteip.internal-proxies"))).isFalse();
    }

    @Test
    void actualBootProdLaunchRejectsDefaultsBeforeContextInitialization() {
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        AtomicBoolean initialized = new AtomicBoolean();
        application.addInitializers(context -> initialized.set(true));
        assertThatThrownBy(() -> application.run("--spring.profiles.active=prod", "--spring.main.banner-mode=off"))
                .hasMessageContaining("Unsafe production setting: spring.datasource.password");
        assertThat(initialized).isFalse();
    }

    @Test
    void publicRealtimeRequiresIndependentManagedPublishAndSessionConfiguration(){
        String prefix="things-link.integration.realtime.mqtt-api.";
        var valid=environment().withProperty("things-link.integration.realtime.enabled","true")
            .withProperty(prefix+"base-url","http://app-broker.internal:18083").withProperty(prefix+"api-key","publisher-managed")
            .withProperty(prefix+"api-secret","managed-publisher-test-secret").withProperty(prefix+"session-api-key","sessions-managed")
            .withProperty(prefix+"session-api-secret","managed-session-test-secret");
        new ProductionEnvironmentPostProcessor().postProcessEnvironment(valid,null);
        for(var invalid:Map.of("base-url","http://user:secret@app-broker.internal","api-key","tc-app-dev-publisher","api-secret","dev-only-app-publisher-secret-do-not-use-in-production","session-api-key","publisher-managed","session-api-secret","").entrySet()){
            var env=environment().withProperty("things-link.integration.realtime.enabled","true");
            for(String key:new String[]{"base-url","api-key","api-secret","session-api-key","session-api-secret"})env.setProperty(prefix+key,valid.getProperty(prefix+key));
            env.setProperty(prefix+invalid.getKey(),invalid.getValue());
            assertThatThrownBy(()->new ProductionEnvironmentPostProcessor().postProcessEnvironment(env,null)).isInstanceOf(IllegalStateException.class).hasMessageContaining(prefix+invalid.getKey()).hasMessageNotContaining("managed-session-test-secret");
        }
    }
    @Test
    void developmentConfigurationRemainsUsable() {
        new ProductionEnvironmentPostProcessor().postProcessEnvironment(new MockEnvironment(), null);
    }

    @Test
    void realPlatformProductionSourceRequiresExplicitMatchingRole() {
        var guard = new ProductionEnvironmentPostProcessor();
        var platform = new SpringApplication(ThingsLinkApplication.class);
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment(), platform))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("things-link.deployment.role");
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                environment().withProperty("things-link.deployment.role", "platform-api"),
                new SpringApplication(EmptyConfiguration.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("things-link.deployment.role");
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                environment().withProperty("things-link.deployment.role", "unknown"),
                new SpringApplication(EmptyConfiguration.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("things-link.deployment.role");
        guard.postProcessEnvironment(environment().withProperty("things-link.deployment.role", "platform-api"),
                platform);
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
