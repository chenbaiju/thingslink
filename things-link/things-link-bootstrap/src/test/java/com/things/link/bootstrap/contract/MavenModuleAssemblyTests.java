package com.things.link.bootstrap.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maven模块装配合同测试。
 *
 * <p>ArchUnit只能检查真实字节码引用，无法发现POM中遗漏的运行时模块或尚未使用就已
 * 形成的依赖环。X-02因此直接读取聚合与模块POM，钉住看板/OTA插槽以及ADR0100要求的
 * 单向依赖；否则后续业务代码可能在本地模块测试通过，却不进入Bootstrap产物。</p>
 */
@DisplayName("Maven模块装配合同（X-02）")
class MavenModuleAssemblyTests {
    /** ThingsLink内部模块的Maven groupId。 */
    private static final String INTERNAL_GROUP = "com.things.link";
    /** X-02首次装配的两个领域模块。 */
    private static final Set<String> NEW_DOMAIN_MODULES = Set.of(
            "things-link-dashboard", "things-link-ota", "things-link-integration");

    /**
     * 聚合、依赖管理、Bootstrap和Flyway扫描位置必须同时登记新模块。
     *
     * @throws Exception POM或配置不可读、XML不安全或格式非法时直接失败
     */
    @Test
    @DisplayName("看板与OTA模块必须完整进入聚合和启动装配")
    void dashboardAndOtaModulesMustBeFullyAssembled() throws Exception {
        Path backend = locateBackendRoot();
        Document rootPom = parsePom(backend.resolve("pom.xml"));
        Set<String> aggregated = childTexts(directChild(rootPom.getDocumentElement(), "modules"), "module");
        Set<String> managed = internalDependencies(
                directChild(directChild(rootPom.getDocumentElement(), "dependencyManagement"), "dependencies"));
        Set<String> bootstrapped = internalDependencies(
                directChild(parsePom(backend.resolve("things-link-bootstrap/pom.xml")).getDocumentElement(),
                        "dependencies"));
        String applicationYaml = Files.readString(
                backend.resolve("things-link-bootstrap/src/main/resources/application.yml"));

        assertThat(aggregated).containsAll(NEW_DOMAIN_MODULES);
        assertThat(managed).containsAll(NEW_DOMAIN_MODULES);
        assertThat(bootstrapped).containsAll(NEW_DOMAIN_MODULES);
        assertThat(applicationYaml)
                .contains("classpath:db/migration/dashboard")
                .contains("classpath:db/migration/ota")
                .contains("classpath:db/migration/integration");
        assertThat(Files.readString(backend.resolve("things-link-bootstrap/src/test/resources/application-test.yml")))
                .contains("classpath:db/migration/integration");
    }

    /**
     * ADR0214：原启动应用与正式普通运行时必须含同一套生产业务模块。
     * ADR0215：独立设备接入装配是第二个启动入口，不进入原平台运行时依赖。
     * ADR0220：发行方模块只进入原管理进程，不进入普通运行时或设备接入进程。
     * 资源用同一源文件构建，避免外部应用在迁移或安全默认值上出现第二份版本。
     */
    @Test
    @DisplayName("正式运行时与原启动应用的生产模块和默认配置一致")
    void officialRuntimeMustMatchBootstrapModulesAndConfiguration() throws Exception {
        Path backend = locateBackendRoot();
        Set<String> modules = childTexts(
                directChild(parsePom(backend.resolve("pom.xml")).getDocumentElement(), "modules"), "module");
        assertThat(modules).contains("things-link-access");
        modules.removeAll(Set.of("things-link-bootstrap", "things-link-runtime",
                "things-link-testing", "things-link-simulator", "things-link-access",
                "things-link-issuer"));
        Set<String> runtime = internalDependencies(directChild(
                parsePom(backend.resolve("things-link-runtime/pom.xml")).getDocumentElement(), "dependencies"));
        Set<String> bootstrap = internalDependencies(directChild(
                parsePom(backend.resolve("things-link-bootstrap/pom.xml")).getDocumentElement(), "dependencies"));
        Set<String> access = internalDependencies(directChild(
                parsePom(backend.resolve("things-link-access/pom.xml")).getDocumentElement(), "dependencies"));

        assertThat(runtime).containsExactlyInAnyOrderElementsOf(modules);
        Set<String> bootstrapDirect = new java.util.HashSet<>(modules);
        // 可复用权益由 runtime 带入管理进程，发行方只由管理进程直接装配。
        bootstrapDirect.remove("things-link-entitlement");
        assertThat(bootstrap).containsAll(bootstrapDirect).contains("things-link-runtime", "things-link-issuer")
                .doesNotContain("things-link-access", "things-link-entitlement");
        assertThat(runtime).doesNotContain("things-link-access", "things-link-issuer");
        assertThat(access).contains("things-link-runtime", "things-link-ingestion");
        assertThat(access).doesNotContain("things-link-issuer");
        assertThat(backend.resolve("things-link-runtime/src/main/java/com/things/link/"
                + "FlywayCompatibilityConfiguration.java")).isRegularFile();
        assertThat(backend.resolve("things-link-bootstrap/src/main/java/com/things/link/"
                + "FlywayCompatibilityConfiguration.java")).doesNotExist();
        assertThat(Files.readAllBytes(backend.resolve("things-link-bootstrap/src/main/resources/application.yml")))
                .isEqualTo(getClass().getClassLoader()
                        .getResourceAsStream("META-INF/things-link/application.yml").readAllBytes());
    }

    /**
     * ADR0100要求的跨模块边必须存在且完整内部依赖图不能形成环。
     *
     * @throws Exception POM不可读或格式非法时直接失败
     */
    @Test
    @DisplayName("冻结的跨模块依赖方向必须存在且无环")
    void frozenModuleDependencyDirectionsMustRemainAcyclic() throws Exception {
        Map<String, Set<String>> graph = internalDependencyGraph(locateBackendRoot());

        assertThat(graph.get("things-link-enduser")).contains("things-link-dashboard");
        assertThat(graph.get("things-link-ingestion")).contains("things-link-enduser");
        assertThat(graph.get("things-link-dashboard"))
                .doesNotContain("things-link-enduser", "things-link-ingestion");
        assertThat(findCycle(graph)).as("内部Maven依赖环").isEmpty();
    }

    /**
     * 新领域模块必须从第一天就保留固定四层包边界，避免先把代码堆在根包再迁移。
     *
     * @throws Exception 源目录不可读时直接失败
     */
    @Test
    @DisplayName("新领域模块必须声明固定四层包边界")
    void newDomainModulesMustDeclareLayerBoundaries() throws Exception {
        Path backend = locateBackendRoot();
        for (String artifact : NEW_DOMAIN_MODULES) {
            String domain = artifact.substring("things-link-".length());
            Path packageRoot = backend.resolve(artifact)
                    .resolve("src/main/java/com/things/link")
                    .resolve(domain);
            for (String layer : List.of("api", "application", "domain", "infrastructure")) {
                assertThat(packageRoot.resolve(layer).resolve("package-info.java"))
                        .as(artifact + " 的 " + layer + " 分层声明")
                        .isRegularFile();
            }
        }
    }

    /**
     * 读取聚合清单中的全部内部模块并构造直接依赖图。
     *
     * @param backend 后端聚合目录
     * @return artifact到直接内部依赖的映射
     * @throws Exception POM不可读或格式非法时直接失败
     */
    private static Map<String, Set<String>> internalDependencyGraph(Path backend) throws Exception {
        Document rootPom = parsePom(backend.resolve("pom.xml"));
        Set<String> modules = childTexts(directChild(rootPom.getDocumentElement(), "modules"), "module");
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String module : modules) {
            Path modulePom = backend.resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(modulePom)) {
                continue;
            }
            Set<String> dependencies = internalDependencies(
                    directChild(parsePom(modulePom).getDocumentElement(), "dependencies"));
            dependencies.retainAll(modules);
            graph.put(module, dependencies);
        }
        return graph;
    }

    /**
     * 在有向图中查找任意依赖环。
     *
     * @param graph 内部模块直接依赖图
     * @return 空列表表示无环，否则返回首个闭合路径
     */
    private static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> active = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String module : graph.keySet()) {
            List<String> cycle = findCycleFrom(module, graph, visited, active, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    /**
     * 深度优先检查一个模块分支；active集合区分回边与已完成分支。
     *
     * @param module 当前模块
     * @param graph 完整依赖图
     * @param visited 已访问模块
     * @param active 当前递归栈模块
     * @param path 当前递归路径
     * @return 空列表或首个闭合环
     */
    private static List<String> findCycleFrom(String module, Map<String, Set<String>> graph,
                                              Set<String> visited, Set<String> active,
                                              Deque<String> path) {
        if (active.contains(module)) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String item : path) {
                collecting |= item.equals(module);
                if (collecting) {
                    cycle.add(item);
                }
            }
            cycle.add(module);
            return cycle;
        }
        if (!visited.add(module)) {
            return List.of();
        }
        active.add(module);
        path.addLast(module);
        for (String dependency : graph.getOrDefault(module, Set.of())) {
            List<String> cycle = findCycleFrom(dependency, graph, visited, active, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.removeLast();
        active.remove(module);
        return List.of();
    }

    /**
     * 从dependencies节点提取ThingsLink内部artifactId。
     *
     * @param dependencies Maven dependencies节点；缺失时允许为空
     * @return 保持POM顺序的内部依赖集合
     */
    private static Set<String> internalDependencies(Element dependencies) {
        Set<String> result = new LinkedHashSet<>();
        if (dependencies == null) {
            return result;
        }
        for (Element dependency : directChildren(dependencies, "dependency")) {
            if (INTERNAL_GROUP.equals(directChildText(dependency, "groupId"))) {
                result.add(directChildText(dependency, "artifactId"));
            }
        }
        return result;
    }

    /**
     * 读取指定直接子节点的文本集合。
     *
     * @param parent 父节点
     * @param name 子节点本地名
     * @return 去除空白且保持声明顺序的文本集合
     */
    private static Set<String> childTexts(Element parent, String name) {
        Set<String> values = new LinkedHashSet<>();
        for (Element child : directChildren(parent, name)) {
            values.add(child.getTextContent().trim());
        }
        return values;
    }

    /**
     * 返回指定名称的首个直接子元素。
     *
     * @param parent 父元素；可以为空
     * @param name 子元素本地名
     * @return 首个匹配元素，缺失时返回null
     */
    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Element child : directChildren(parent, name)) {
            return child;
        }
        return null;
    }

    /**
     * 返回指定直接子元素的文本。
     *
     * @param parent 父元素
     * @param name 子元素本地名
     * @return 去除空白的文本；缺失时为空字符串
     */
    private static String directChildText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    /**
     * 枚举指定名称的直接子元素，避免后代查询把插件依赖误算为模块依赖。
     *
     * @param parent 父元素；可以为空
     * @param name 子元素本地名
     * @return 匹配的直接子元素
     */
    private static List<Element> directChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        if (parent == null) {
            return children;
        }
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    /**
     * 使用禁用外部实体的JDK解析器读取可信仓库POM。
     *
     * @param pom POM路径
     * @return DOM文档
     * @throws Exception 文件不可读、XML不安全或格式非法时直接失败
     */
    private static Document parsePom(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    /**
     * 从Maven或IDE工作目录向上定位后端聚合目录。
     *
     * @return 同时包含根POM和Bootstrap模块的后端目录
     */
    private static Path locateBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("things-link-bootstrap/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位things-link后端聚合目录");
    }
}
