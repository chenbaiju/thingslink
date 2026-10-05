package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 固定执行原文解析再执行内部语义内核的默认门面实现。 */
final class DefaultDashboardSchemaValidator implements DashboardSchemaValidator {
    /** 注入默认值后的完整Schema最大UTF-8字节数。 */
    private static final int MAX_NORMALIZED_BYTES = 512_000;
    /** 根对象在当前合同中允许的封闭字段。 */
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "presentation", "models", "variables", "pages");
    /** 严格原文解析端口。 */
    private final DashboardSchemaParser parser;

    /**
     * 创建固定顺序的校验门面。
     *
     * @param parser 严格原文解析端口
     */
    DefaultDashboardSchemaValidator(DashboardSchemaParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public DashboardSchemaValidationResult validateAndNormalize(byte[] source) {
        ParsedDashboardSchema parsed = parser.parse(source);
        ObjectNode root = DashboardSchemaStructureRules.requireObject(parsed.root(), "$", ROOT_FIELDS);
        DashboardSchemaStructureRules.rejectNulls(root, "$");
        String schemaVersion = DashboardSchemaStructureRules.requireString(root, "schemaVersion", "$");
        if (!"tc.dashboard/v1".equals(schemaVersion)) {
            throw invalidValue("$.schemaVersion", "必须精确为tc.dashboard/v1");
        }
        ObjectNode presentation = DashboardSchemaStructureRules.requireObject(
                DashboardSchemaStructureRules.required(root, "presentation", "$"), "$.presentation");
        DashboardSchemaStructureRules.defaultArray(root, "models");
        DashboardSchemaStructureRules.defaultArray(root, "variables");
        ArrayNode models = DashboardSchemaStructureRules.requireArray(root.get("models"), "$.models");
        ArrayNode variables = DashboardSchemaStructureRules.requireArray(root.get("variables"), "$.variables");
        ArrayNode pages = DashboardSchemaStructureRules.requireArray(
                DashboardSchemaStructureRules.required(root, "pages", "$"), "$.pages");
        CanvasMode canvasMode = validatePresentation(presentation);
        List<DashboardSchemaExternalRequirement> requirements = new ArrayList<>();
        Set<String> modelKeys = validateModels(models, requirements);
        DashboardSchemaVariableCatalog variableCatalog =
                DashboardSchemaVariableCatalog.validateAndNormalize(variables, modelKeys);
        collectDefaultDeviceRequirements(variables, variableCatalog, requirements);
        requirements.addAll(validatePages(pages, canvasMode, variableCatalog));
        if (root.toString().getBytes(StandardCharsets.UTF_8).length > MAX_NORMALIZED_BYTES) {
            throw reject(DashboardSchemaValidationException.Reason.NORMALIZED_TOO_LARGE,
                    "$", "注入默认值后超过512000字节上限");
        }
        return new DashboardSchemaValidationResult(
                DashboardSchemaValidationResult.Scope
                        .COMPLETE_INTERNAL_SCHEMA_SEMANTICS,
                root, requirements);
    }

    /** 校验两种画布判别结构并注入唯一默认值。 */
    private static CanvasMode validatePresentation(ObjectNode node) {
        String modeValue = DashboardSchemaStructureRules.requireString(node, "mode", "$.presentation");
        CanvasMode mode = enumValue(CanvasMode.class, modeValue, "$.presentation.mode");
        DashboardSchemaStructureRules.putDefault(node, "theme", "LIGHT");
        requireEnum(node.get("theme"), "$.presentation.theme", Set.of("LIGHT", "DARK"));
        if (mode == CanvasMode.RESPONSIVE_GRID) {
            DashboardSchemaStructureRules.requireObject(node, "$.presentation",
                    Set.of("mode", "theme", "columns", "rowHeight", "gap"));
            DashboardSchemaStructureRules.putDefault(node, "columns", 24);
            DashboardSchemaStructureRules.putDefault(node, "rowHeight", 8);
            DashboardSchemaStructureRules.putDefault(node, "gap", 8);
            requireExactInteger(node, "columns", "$.presentation", 24);
            requireExactInteger(node, "rowHeight", "$.presentation", 8);
            requireExactInteger(node, "gap", "$.presentation", 8);
        } else {
            DashboardSchemaStructureRules.requireObject(node, "$.presentation",
                    Set.of("mode", "theme", "width", "height", "scaleMode"));
            DashboardSchemaStructureRules.putDefault(node, "width", 1920);
            DashboardSchemaStructureRules.putDefault(node, "height", 1080);
            DashboardSchemaStructureRules.putDefault(node, "scaleMode", "FIT");
            requireExactInteger(node, "width", "$.presentation", 1920);
            requireExactInteger(node, "height", "$.presentation", 1080);
            requireEnum(node.get("scaleMode"), "$.presentation.scaleMode", Set.of("FIT"));
        }
        return mode;
    }

    /** 校验模型引用数量、封闭字段、值规则与key/versionId双重唯一性。 */
    private static Set<String> validateModels(
            ArrayNode models, List<DashboardSchemaExternalRequirement> requirements) {
        requireSize(models, "$.models", 0, 20);
        Set<String> keys = new HashSet<>();
        Set<String> versionIds = new HashSet<>();
        for (int index = 0; index < models.size(); index++) {
            String path = "$.models[" + index + "]";
            ObjectNode model = DashboardSchemaStructureRules.requireObject(models.get(index), path,
                    Set.of("key", "versionId", "digestAlgorithm", "digest", "profile"));
            String key = DashboardSchemaValueRules.localKey(required(model, "key", path), path + ".key");
            String versionId = DashboardSchemaValueRules.uuid(required(model, "versionId", path), path + ".versionId");
            String digestAlgorithm = DashboardSchemaStructureRules.requireString(model, "digestAlgorithm", path);
            String digest = DashboardSchemaValueRules.sha256(required(model, "digest", path), path + ".digest");
            String profile = DashboardSchemaStructureRules.requireString(model, "profile", path);
            if (!"PG_JSONB_TEXT_V1_SHA256".equals(digestAlgorithm)
                    || !"TC_PROPERTY_COMPOSITE_V1".equals(profile)) {
                throw invalidValue(path, "模型摘要算法或Profile不受支持");
            }
            if (!keys.add(key) || !versionIds.add(versionId)) {
                throw invalidCollection(path, "模型key和versionId都必须全文唯一");
            }
            requirements.add(new ModelReferenceRequirement(
                    key, versionId, digestAlgorithm, digest, profile));
        }
        return Set.copyOf(keys);
    }

    /** 收集默认设备的存在、模型匹配及运行时授权重验需求，不把Schema引用当作授权事实。 */
    private static void collectDefaultDeviceRequirements(
            ArrayNode variables, DashboardSchemaVariableCatalog catalog,
            List<DashboardSchemaExternalRequirement> requirements) {
        for (JsonNode variableNode : variables) {
            ObjectNode variable = (ObjectNode) variableNode;
            String variableKey = variable.get("key").asString();
            DashboardSchemaVariableCatalog.VariableDefinition definition = catalog.definition(variableKey);
            if (definition.type() == DashboardSchemaVariableCatalog.VariableType.DEVICE_SINGLE
                    && variable.has("defaultDeviceId")) {
                requirements.add(new DefaultDeviceRequirement(
                        variableKey, definition.type(), definition.modelKey(),
                        variable.get("defaultDeviceId").asString()));
            }
            if (definition.type() == DashboardSchemaVariableCatalog.VariableType.DEVICE_MULTI) {
                for (JsonNode deviceId : variable.get("defaultDeviceIds")) {
                    requirements.add(new DefaultDeviceRequirement(
                            variableKey, definition.type(), definition.modelKey(), deviceId.asString()));
                }
            }
        }
    }

    /** 校验页面数量、全局页面/组件id、组件壳及同页布局。 */
    private static List<DashboardSchemaExternalRequirement> validatePages(
            ArrayNode pages, CanvasMode mode, DashboardSchemaVariableCatalog variables) {
        requireSize(pages, "$.pages", 1, mode == CanvasMode.RESPONSIVE_GRID ? 5 : 1);
        Set<String> pageIds = new HashSet<>();
        Set<String> componentIds = new HashSet<>();
        List<DashboardSchemaExternalRequirement> requirements = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String pagePath = "$.pages[" + pageIndex + "]";
            ObjectNode page = DashboardSchemaStructureRules.requireObject(
                    pages.get(pageIndex), pagePath, Set.of("id", "title", "components"));
            String pageId = DashboardSchemaValueRules.localKey(required(page, "id", pagePath), pagePath + ".id");
            if (!pageIds.add(pageId)) throw invalidCollection(pagePath, "页面id必须全文唯一");
            DashboardSchemaValueRules.title(required(page, "title", pagePath), pagePath + ".title");
            ArrayNode components = DashboardSchemaStructureRules.requireArray(
                    required(page, "components", pagePath), pagePath + ".components");
            requireSize(components, pagePath + ".components", 0, 50);
            List<Rectangle> rectangles = new ArrayList<>();
            for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
                String path = pagePath + ".components[" + componentIndex + "]";
                ObjectNode component = DashboardSchemaStructureRules.requireObject(components.get(componentIndex), path,
                        Set.of("id", "kind", "componentVersion", "layout", "props", "bindings"));
                Rectangle rectangle = validateComponentShell(component, path, mode, componentIds);
                requirements.addAll(DashboardComponentSemanticRules.validateAndNormalize(
                        component, path, variables));
                for (Rectangle previous : rectangles) {
                    if (rectangle.overlaps(previous)) {
                        throw invalidLayout(path + ".layout", "同一页组件矩形不得重叠");
                    }
                }
                rectangles.add(rectangle);
            }
        }
        return List.copyOf(requirements);
    }

    /** 校验组件共同字段和容器，不判断props与slot组合语义。 */
    private static Rectangle validateComponentShell(
            ObjectNode component, String path, CanvasMode mode, Set<String> componentIds) {
        String id = DashboardSchemaValueRules.localKey(required(component, "id", path), path + ".id");
        if (!componentIds.add(id)) throw invalidCollection(path, "组件id必须全文唯一");
        String kind = DashboardSchemaStructureRules.requireString(component, "kind", path);
        String version = DashboardSchemaValueRules.semVer(
                required(component, "componentVersion", path), path + ".componentVersion");
        DashboardComponentDescriptorRegistry.require(kind, version, path);
        Rectangle rectangle = validateLayout(DashboardSchemaStructureRules.requireObject(
                required(component, "layout", path), path + ".layout", Set.of("x", "y", "w", "h")),
                path + ".layout", mode);
        DashboardSchemaStructureRules.requireObject(required(component, "props", path), path + ".props");
        DashboardSchemaStructureRules.requireObject(required(component, "bindings", path), path + ".bindings");
        return rectangle;
    }

    /** 校验两种画布下的矩形整数范围和不越界条件。 */
    private static Rectangle validateLayout(ObjectNode layout, String path, CanvasMode mode) {
        int maximumX = mode == CanvasMode.RESPONSIVE_GRID ? 23 : 1919;
        int maximumY = mode == CanvasMode.RESPONSIVE_GRID ? 999 : 1079;
        int maximumWidth = mode == CanvasMode.RESPONSIVE_GRID ? 24 : 1920;
        int maximumHeight = mode == CanvasMode.RESPONSIVE_GRID ? 1000 : 1080;
        int x = requireInteger(layout, "x", path, 0, maximumX);
        int y = requireInteger(layout, "y", path, 0, maximumY);
        int width = requireInteger(layout, "w", path, 1, maximumWidth);
        int height = requireInteger(layout, "h", path, 1, maximumHeight);
        if (x + width > maximumWidth || y + height > maximumHeight) {
            throw invalidLayout(path, "组件矩形越过画布边界");
        }
        return new Rectangle(x, y, width, height);
    }

    /** 返回必填字段。 */
    private static JsonNode required(ObjectNode node, String name, String path) {
        return DashboardSchemaStructureRules.required(node, name, path);
    }

    /** 要求枚举字符串属于闭集。 */
    private static String requireEnum(JsonNode node, String path, Set<String> allowed) {
        if (!node.isString()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH, path, "必须是字符串");
        }
        if (!allowed.contains(node.asString())) throw invalidValue(path, "枚举值不受支持");
        return node.asString();
    }

    /** 将字符串转换为已登记枚举。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalidValue(path, "枚举值不受支持");
        }
    }

    /** 要求JSON整数处于闭区间。 */
    private static int requireInteger(ObjectNode node, String name, String path, int minimum, int maximum) {
        long value = DashboardSchemaStructureRules.requireInteger(node, name, path);
        if (value < minimum || value > maximum) throw invalidValue(path + "." + name, "整数超出允许范围");
        return (int) value;
    }

    /** 要求JSON整数等于合同冻结值。 */
    private static void requireExactInteger(ObjectNode node, String name, String path, int expected) {
        requireInteger(node, name, path, expected, expected);
    }

    /** 要求数组数量处于闭区间。 */
    private static void requireSize(ArrayNode node, String path, int minimum, int maximum) {
        if (node.size() < minimum || node.size() > maximum) {
            throw invalidCollection(path, "数量必须在" + minimum + "至" + maximum + "之间");
        }
    }

    /** 创建普通值错误。 */
    private static DashboardSchemaValidationException invalidValue(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_VALUE, path, detail);
    }

    /** 创建集合错误。 */
    private static DashboardSchemaValidationException invalidCollection(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_COLLECTION, path, detail);
    }

    /** 创建布局错误。 */
    private static DashboardSchemaValidationException invalidLayout(String path, String detail) {
        return reject(DashboardSchemaValidationException.Reason.INVALID_LAYOUT, path, detail);
    }

    /** 创建稳定原因异常。 */
    private static DashboardSchemaValidationException reject(
            DashboardSchemaValidationException.Reason reason, String path, String detail) {
        return new DashboardSchemaValidationException(reason, path, detail);
    }

    /**
     * 画布整数矩形。
     *
     * @param x 左上角横坐标
     * @param y 左上角纵坐标
     * @param width 矩形宽度
     * @param height 矩形高度
     */
    private record Rectangle(int x, int y, int width, int height) {
        /** 判断两个矩形是否有正面积交叠，接边不算重叠。 */
        private boolean overlaps(Rectangle other) {
            return x < other.x + other.width && other.x < x + width
                    && y < other.y + other.height && other.y < y + height;
        }
    }

    /** 两种冻结画布模式。 */
    private enum CanvasMode {
        /** 二十四列响应式网格。 */ RESPONSIVE_GRID,
        /** 1920乘1080固定画布。 */ FIXED_SCREEN
    }

}
