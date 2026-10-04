package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.draft.DashboardDraftContractValidator;
import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 看板草稿严格解析器与公开校验门面的最小Spring生产装配测试。 */
@DisplayName("看板草稿校验Spring装配")
class DashboardDraftValidatorSpringConfigurationTests {

    /** 组件扫描必须只产生一个严格解析端口，并能构造可执行的公开草稿门面。 */
    @Test
    @DisplayName("唯一严格解析器注入公开草稿门面")
    void singleStrictParserInjectsPublicDraftValidator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(JacksonDashboardSchemaParser.class, DefaultDashboardDraftContractValidator.class);
            context.refresh();

            assertThat(context.getBeansOfType(DashboardSchemaParser.class).values())
                    .singleElement().isInstanceOf(JacksonDashboardSchemaParser.class);
            DashboardDraftContractValidator validator =
                    context.getBean(DashboardDraftContractValidator.class);
            assertThat(validator).isInstanceOf(DefaultDashboardDraftContractValidator.class);
            assertThat(validator.validate("0", minimalSchema()).expectedRevision()).isZero();
        }
    }

    /** 构造不含外部模型的最小完整看板Schema。 */
    private byte[] minimalSchema() {
        return """
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},
                 "pages":[{"id":"overview","title":"设备概览","components":[]}]}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
