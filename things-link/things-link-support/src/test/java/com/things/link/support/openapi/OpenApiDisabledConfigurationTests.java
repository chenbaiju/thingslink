package com.things.link.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 生产关闭 springdoc 时，其内部定制器 Bean 不应阻止平台启动。 */
class OpenApiDisabledConfigurationTests {

    @Test
    void excludesAllProjectOpenApiBeansWhenApiDocsAreDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenApiConfiguration.class)
                .withPropertyValues("springdoc.api-docs.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpenApiConfiguration.class);
                    assertThat(context).doesNotHaveBean("thingsLinkOpenApi");
                    assertThat(context).doesNotHaveBean("sameOriginServerCustomizer");
                    assertThat(context).doesNotHaveBean("globalStructureLast");
                });
    }
}
