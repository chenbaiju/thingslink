package com.things.link.bootstrap.contract;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import tools.jackson.databind.JsonNode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 从所有模块的真实源码提取 HTTP 映射和握手注册，不依赖 Controller 文件名或旧接口数量。 */
final class OpenApiHttpInventoryAssertions {
    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static Set<String> sourceEntries;

    private OpenApiHttpInventoryAssertions() { }

    /** 生成前同时核对覆盖、中文操作、分组、参数及响应说明；不能只比较两份同样漏项的 JSON。 */
    static void verify(JsonNode spec) throws Exception {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (VERBS.contains(method.getKey())) operations.put(method.getKey() + " " + path.getKey(), method.getValue());
        }));
        assertThat(operations.keySet()).as("全部模块的源码 HTTP 入口与文档必须逐项一致")
                .containsExactlyInAnyOrderElementsOf(sourceEntries());
        Map<String, JsonNode> tags = new LinkedHashMap<>();
        spec.path("tags").valueStream().forEach(tag -> {
            String name = tag.path("name").asString();
            assertChinese(name, "功能分组名称");
            assertChinese(tag.path("description").asString(), "功能分组「" + name + "」说明");
            assertThat(tags.putIfAbsent(name, tag)).as("重复功能分组 %s", name).isNull();
        });
        operations.forEach((label, operation) -> {
            assertChinese(operation.path("summary").asString(), label + " 摘要");
            assertChinese(operation.path("description").asString(), label + " 详细说明");
            assertThat(operation.path("tags").isArray() && !operation.path("tags").isEmpty()).as(label + " 分组").isTrue();
            operation.path("tags").valueStream().forEach(tag -> {
                assertChinese(tag.asString(), label + " 分组名称");
                assertThat(tags).as(label + " 分组须登记，不能退回 Controller 英文类名").containsKey(tag.asString());
            });
            operation.path("parameters").valueStream().forEach(parameter ->
                    assertChinese(parameter.path("description").asString(), label + " 参数 " + parameter.path("name").asString()));
            operation.path("responses").properties().forEach(response ->
                    assertChinese(response.getValue().path("description").asString(), label + " 响应 " + response.getKey()));
            if (operation.has("requestBody")) assertChinese(operation.path("requestBody").path("description").asString(), label + " 请求体说明");
        });
    }

    private static void assertChinese(String value, String label) {
        assertThat(value != null && value.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff))
                .as(label + " 必须包含中文说明").isTrue();
    }

    /** 编译器只解析语法树；不执行源码、不解析业务依赖、不以易漏常量或数组的正则替代映射。 */
    static synchronized Set<String> sourceEntries() throws Exception {
        if (sourceEntries != null) return sourceEntries;
        Path root = OpenApiGenerationWriteGuard.canonicalRepositoryOutput(OpenApiSpecTests.class)
                .getParent().getParent().resolve("things-link");
        List<Path> sources = new ArrayList<>();
        try (var modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path java = module.resolve("src/main/java");
                if (!Files.isDirectory(java)) continue;
                try (var files = Files.walk(java)) {
                    sources.addAll(files.filter(file -> file.toString().endsWith(".java")).sorted().toList());
                }
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("HTTP 全模块审计需要 JDK 编译器").isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Set<String> entries = new LinkedHashSet<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(sources));
            DocTrees docs = DocTrees.instance(task);
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        ClassTree owner = owner(getCurrentPath());
                        Map<String, ExpressionTree> constants = constants(owner);
                        for (var annotation : method.getModifiers().getAnnotations()) {
                            String type = simple(annotation.getAnnotationType().toString());
                            if (!type.matches("(Get|Post|Put|Patch|Delete|Head|Options|Request)Mapping")) continue;
                            DocCommentTree comment = docs.getDocCommentTree(getCurrentPath());
                            assertThat(comment).as(unit.getSourceFile().getName() + "#" + method.getName()).isNotNull();
                            assertChinese(String.join("", comment.getFullBody().stream().map(tree -> tree instanceof com.sun.source.doctree.TextTree text ? text.getBody() : tree.toString()).toList()),
                                    owner.getSimpleName() + "#" + method.getName() + " 源码用途");
                            for (var parameter : method.getParameters()) {
                                var parameterDoc = comment.getBlockTags().stream()
                                        .filter(tag -> tag instanceof com.sun.source.doctree.ParamTree value
                                                && value.getName().toString().equals(parameter.getName().toString()))
                                        .map(tag -> (com.sun.source.doctree.ParamTree) tag).findFirst();
                                assertThat(parameterDoc).as(owner.getSimpleName() + "#" + method.getName()
                                        + " 必须逐项说明参数 " + parameter.getName()).isPresent();
                                assertChinese(docText(parameterDoc.orElseThrow().getDescription()),
                                        owner.getSimpleName() + "#" + method.getName() + " 参数 " + parameter.getName());
                            }
                            if (!method.getReturnType().toString().equals("void")) {
                                var returns = comment.getBlockTags().stream()
                                        .filter(tag -> tag instanceof com.sun.source.doctree.ReturnTree)
                                        .map(tag -> (com.sun.source.doctree.ReturnTree) tag).findFirst();
                                assertThat(returns).as(owner.getSimpleName() + "#" + method.getName() + " 必须说明返回结果").isPresent();
                                assertChinese(docText(returns.orElseThrow().getDescription()), owner.getSimpleName() + "#" + method.getName() + " 返回值");
                            }
                            List<String> prefixes = owner.getModifiers().getAnnotations().stream()
                                    .filter(a -> simple(a.getAnnotationType().toString()).equals("RequestMapping"))
                                    .findFirst().map(a -> paths(a, constants)).orElse(List.of(""));
                            List<String> verbs = type.equals("RequestMapping") ? requestVerbs(annotation)
                                    : List.of(type.replace("Mapping", "").toLowerCase(java.util.Locale.ROOT));
                            for (String prefix : prefixes) for (String suffix : paths(annotation, constants))
                                for (String verb : verbs) add(entries, verb + " " + normalize(prefix + suffix));
                        }
                        return super.visitMethod(method, unused);
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                        if (invocation.getMethodSelect().toString().endsWith(".addHandler") && invocation.getArguments().size() >= 2) {
                            Map<String, ExpressionTree> constants = constants(owner(getCurrentPath()));
                            for (var path : invocation.getArguments().subList(1, invocation.getArguments().size()))
                                for (String pattern : strings(path, constants)) add(entries, "get " + normalize(pattern));
                        }
                        return super.visitMethodInvocation(invocation, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList())
                .as("源码解析失败不能当作接口不存在").isEmpty();
        // Spring MVC 为静态 GET 提供 HEAD；逐项登记，不展开所有 GET 的隐式 HEAD/OPTIONS。
        add(entries, "head /app");
        add(entries, "head /app/{resourcePath}");
        // 框架默认配置暴露的健康、指标及索引另由运行注册表回归核对。
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/health/{componentPath}", "/actuator/prometheus"))
            add(entries, "get " + path);
        sourceEntries = Set.copyOf(entries);
        return sourceEntries;
    }

    private static ClassTree owner(TreePath path) {
        while (path != null && !(path.getLeaf() instanceof ClassTree)) path = path.getParentPath();
        if (path == null) throw new IllegalStateException("HTTP 注册缺少所属类型");
        return (ClassTree) path.getLeaf();
    }

    private static Map<String, ExpressionTree> constants(ClassTree owner) {
        Map<String, ExpressionTree> values = new LinkedHashMap<>();
        for (var member : owner.getMembers()) if (member instanceof VariableTree variable && variable.getInitializer() != null)
            values.put(variable.getName().toString(), variable.getInitializer());
        return values;
    }

    private static List<String> paths(AnnotationTree annotation, Map<String, ExpressionTree> constants) {
        for (var argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment) {
                if (Set.of("path", "value").contains(assignment.getVariable().toString())) return strings(assignment.getExpression(), constants);
            } else return strings(argument, constants);
        }
        return List.of("");
    }

    private static List<String> strings(ExpressionTree expression, Map<String, ExpressionTree> constants) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) return List.of(value);
        if (expression instanceof NewArrayTree array) return array.getInitializers().stream().flatMap(value -> strings(value, constants).stream()).toList();
        if (expression instanceof IdentifierTree identifier && constants.containsKey(identifier.getName().toString()))
            return strings(constants.get(identifier.getName().toString()), constants);
        throw new IllegalStateException("未支持的 HTTP 路径表达式，必须显式扩展审计而不能跳过：" + expression);
    }

    private static List<String> requestVerbs(AnnotationTree annotation) {
        for (var argument : annotation.getArguments()) if (argument instanceof AssignmentTree assignment
                && assignment.getVariable().toString().equals("method")) {
            var value = assignment.getExpression();
            var expressions = value instanceof NewArrayTree array ? array.getInitializers() : List.of(value);
            return expressions.stream().map(v -> simple(v.toString()).toLowerCase(java.util.Locale.ROOT)).toList();
        }
        return VERBS.stream().sorted().toList();
    }

    private static String simple(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static String docText(List<? extends com.sun.source.doctree.DocTree> text) {
        return String.join("", text.stream().map(tree -> tree instanceof com.sun.source.doctree.TextTree value
                ? value.getBody() : tree.toString()).toList());
    }
    private static String normalize(String pattern) { return pattern.replace("/app/**", "/app/{resourcePath}").replace("/shares/*/", "/shares/{shareId}/"); }
    private static void add(Set<String> entries, String entry) {
        assertThat(entries.add(entry)).as("源码存在重复 HTTP 映射 %s；必须显式区分条件而非静默去重", entry).isTrue();
    }
}
