package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleMessage;
import tools.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 动作节点共用的确定性模板渲染器；只暴露可信身份和 payload 标量，不执行表达式或脚本。 */
final class RuleActionTemplateRenderer {
    /** payload 路径只允许有限标识符，避免把模板扩成 JSONPath 或动态代码执行入口。 */
    private static final Pattern PAYLOAD_PLACEHOLDER = Pattern.compile("\\$\\{payload\\.([A-Za-z0-9_.]+)}");

    /** 工具类不允许实例化。 */
    private RuleActionTemplateRenderer() { }

    /** 替换可信身份与 payload 标量；无法解析的路径渲染为空串，保持重放结果确定。 */
    static String render(String template, RuleMessage message) {
        String rendered = template
                .replace("${deviceId}", message.deviceId().toString())
                .replace("${messageId}", message.messageId().toString())
                .replace("${traceId}", message.traceId())
                .replace("${occurredAt}", message.occurredAt().toString());
        Matcher matcher = PAYLOAD_PLACEHOLDER.matcher(rendered);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolve(message.payload(), matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 沿点分路径读取标量，拒绝把对象或数组隐式序列化到外发正文。 */
    private static String resolve(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject() || !current.has(segment)) return "";
            current = current.get(segment);
        }
        return current == null || !current.isValueNode() ? "" : current.asText();
    }
}
