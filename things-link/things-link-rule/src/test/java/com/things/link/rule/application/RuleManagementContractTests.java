package com.things.link.rule.application;

import com.things.link.rule.application.engine.node.AlarmClearActionNode;
import com.things.link.rule.application.engine.node.AlarmCreateActionNode;
import com.things.link.rule.application.engine.node.DeviceCommandActionNode;
import com.things.link.rule.application.engine.node.DevicePropertySetActionNode;
import com.things.link.rule.application.engine.node.EmailActionNode;
import com.things.link.rule.application.engine.node.MessageTypeFilterNode;
import com.things.link.rule.application.engine.node.MetadataEnrichmentNode;
import com.things.link.rule.application.engine.node.NotificationActionNode;
import com.things.link.rule.application.engine.node.PayloadPropertyCompareNode;
import com.things.link.rule.application.engine.node.WebhookActionNode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 管理输入必须拒绝跨用途、游标换项目和有损历史回读。 */
class RuleManagementContractTests {
    /** JSON事实与配置。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 真节点覆盖全部七类Schema，不替换validate。 */
    private final RuleNodeCatalog catalog = new RuleNodeCatalog(List.of(new NotificationActionNode(),
            new EmailActionNode(), new WebhookActionNode(), new DeviceCommandActionNode(),
            new DevicePropertySetActionNode(), new AlarmCreateActionNode(), new AlarmClearActionNode(),
            new PayloadPropertyCompareNode(), new MetadataEnrichmentNode(), new MessageTypeFilterNode()), mapper);

    /** 合法注册节点不等于允许该用途；畸形请求不得转为500。 */
    @Test void rejectsWrongPurposeAndNullAndPreservesSevenDescriptors() {
        assertThat(catalog.view().actions()).hasSize(7);
        assertThat(catalog.view().conditions()).extracting(RuleNodeCatalog.Descriptor::nodeType).containsExactly("payload-property-compare");
        var comparison = mapper.readTree("{\"pointer\":\"/a\",\"operator\":\"EXISTS\"}");
        catalog.validate("payload-property-compare", comparison, true);
        assertThatThrownBy(() -> catalog.validate("payload-property-compare", comparison, false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.validate("notification-action", mapper.createObjectNode(), true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.validate(null, null, false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.validate("notification-action", mapper.readTree("{\"channel\":\"EMAIL\",\"recipient\":\"a@example.com\",\"unexpected\":true}"), false)).isInstanceOf(BusinessException.class);
    }

    /** 新编辑32项与UTF8预算，历史合法数组不被悄悄截断。 */
    @Test void boundsNewWritesWithoutRewritingHistoricalArrays() {
        var action = new ActionSpec("notification-action", mapper.readTree("{\"channel\":\"EMAIL\",\"recipient\":\"a@example.com\"}"));
        catalog.budget(List.of(), java.util.Collections.nCopies(32, action), false);
        assertThatThrownBy(() -> catalog.budget(List.of(), java.util.Collections.nCopies(33, action), false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.budget(List.of(), List.of("字".repeat(23000)), false)).isInstanceOf(BusinessException.class);
        var historical = mapper.valueToTree(java.util.Collections.nCopies(33, action));
        catalog.historical(historical, false);
        assertThat(historical.size()).isEqualTo(33);
    }

    /** 游标版本必须为精确整数，不能截断小数或溢出后误识别为v1。 */
    @Test void cursorVersionRejectsFractionAndOverflow() {
        String scope = RuleManagementCursor.scope(UUID.randomUUID(), "scene", "", "");
        for (String version : List.of("1.25", "4294967297")) {
            var node = mapper.createObjectNode().put("scope", scope).put("key", 1);
            node.set("v", mapper.readTree(version));
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> RuleManagementCursor.decode(encoded, scope, true, true)).isInstanceOf(BusinessException.class);
        }
    }

    /** 游标绑定筛选和项目；输入中的位置不能注入SQL或变成授权。 */
    @Test void cursorRejectsDifferentScopeMalformedAndUnboundedInputs() {
        String scope = RuleManagementCursor.scope(UUID.randomUUID(), "message", "温度", "ACTIVE");
        var id = UUID.randomUUID();
        var time = java.time.Instant.now();
        String value = RuleManagementCursor.encode(scope, List.of(time.toString(), id.toString()));
        var position = RuleManagementCursor.decode(value, scope, false, false);
        assertThat(position.id()).isEqualTo(id); assertThat(position.time()).isEqualTo(time);
        assertThatThrownBy(() -> RuleManagementCursor.decode(value, scope + "x", false, false)).isInstanceOf(BusinessException.class);
        for (String malformed : List.of("", "not json", "x".repeat(2049), RuleManagementCursor.encode(scope, -1)))
            assertThatThrownBy(() -> RuleManagementCursor.decode(malformed, scope, false, false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RuleManagementCursor.status("DELETED", false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RuleManagementCursor.limit(101, false)).isInstanceOf(BusinessException.class);
    }
}
