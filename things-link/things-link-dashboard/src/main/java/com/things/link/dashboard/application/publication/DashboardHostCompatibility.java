package com.things.link.dashboard.application.publication;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** ADR0147：只检查封存的宿主需求，不重新解释业务数据或改写历史版本。 */
public final class DashboardHostCompatibility {
    private DashboardHostCompatibility() { }
    /** 全局预检必须覆盖的运行入口。 */
    public enum Kind { APPLICATION, DASHBOARD, SHARE }
    /** 不含业务正文、名称或分享凭据的固定诊断。 */
    public enum Reason { INVALID_METADATA, FORMAT, HOST_RANGE, SCHEMA, COMPONENT, RESOURCE }

    /**
     * 检查数据库投影的完整需求封套，字段缺失/扩展和重复项均拒绝。
     * @param host 已经由完整文件读取核验的目标能力
     * @param kind 运行事实种类
     * @param value 格式/范围/Schema/组件/资源的封存投影
     * @return 不兼容原因；空表示本条匹配，不表示全库或激活已通过
     */
    public static Optional<Reason> inspect(DashboardHostQualificationDescriptor host, Kind kind, JsonNode value) {
        try {
            if (!fields(value, Set.of("format", "hostCompatibility", "schemas", "components", "resources"))) return fail(Reason.INVALID_METADATA);
            String format = text(value.get("format"));
            if (kind == Kind.APPLICATION ? !host.supportedApplicationFormats().contains(format) : !host.supportedSchemas().contains(format)) return fail(Reason.FORMAT);
            JsonNode range = value.get("hostCompatibility");
            if (kind == Kind.DASHBOARD) {
                if (!range.isNull()) return fail(Reason.INVALID_METADATA);
            } else {
                if (!fields(range, Set.of("minInclusive", "maxExclusive"))) return fail(Reason.INVALID_METADATA);
                if (!ApplicationPublicationQualificationService.hostVersionInRange(host.hostVersion(),
                        text(range.get("minInclusive")), text(range.get("maxExclusive")))) return fail(Reason.HOST_RANGE);
            }
            JsonNode schemas = value.get("schemas");
            if (!schemas.isArray() || schemas.size() != 1) return fail(Reason.INVALID_METADATA);
            if (!host.supportedSchemas().contains(text(schemas.get(0)))) return fail(Reason.SCHEMA);
            JsonNode components = value.get("components"), resources = value.get("resources");
            if (!components.isArray() || components.size() > 10 || !resources.isArray() || resources.size() > 50) return fail(Reason.INVALID_METADATA);
            Set<String> kinds = new HashSet<>();
            for (JsonNode component : components) {
                if (!fields(component, Set.of("kind", "componentVersion"))) return fail(Reason.INVALID_METADATA);
                String name = text(component.get("kind"));
                if (!kinds.add(name)) return fail(Reason.INVALID_METADATA);
                DashboardPublicationEligibilityRequirement.ComponentKind componentKind;
                try { componentKind = DashboardPublicationEligibilityRequirement.ComponentKind.valueOf(name); }
                catch (IllegalArgumentException unsupported) { return fail(Reason.COMPONENT); }
                if (!text(component.get("componentVersion")).equals(host.componentVersions().get(componentKind))) return fail(Reason.COMPONENT);
            }
            Set<String> resourceIds = new HashSet<>();
            for (JsonNode resource : resources) {
                if (!fields(resource, Set.of("resourceId", "digest"))) return fail(Reason.INVALID_METADATA);
                String id = text(resource.get("resourceId")), digest = text(resource.get("digest"));
                if (!id.matches("[a-z][a-z0-9_]{0,63}") || !digest.matches("[a-f0-9]{64}") || !resourceIds.add(id)) return fail(Reason.INVALID_METADATA);
                if (!digest.equals(host.resourceDigests().get(id))) return fail(Reason.RESOURCE);
            }
            return Optional.empty();
        } catch (IllegalArgumentException malformed) { return fail(Reason.INVALID_METADATA); }
    }
    private static Optional<Reason> fail(Reason reason) { return Optional.of(reason); }
    private static boolean fields(JsonNode node, Set<String> names) {
        return node != null && node.isObject() && node.propertyNames().equals(names);
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isString()) throw new IllegalArgumentException("需求字段类型无效");
        return node.asString();
    }
}
