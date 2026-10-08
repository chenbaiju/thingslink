package com.things.link.iam.application;

import com.things.link.shared.authz.ProjectRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** D-004：快照由真实目录/角色过滤生成，前端另用实际菜单处理器校验同一快照。 */
class MenuCatalogContractTests {
    @Test void productionCatalogAndPermissionsMatchCrossLanguageBaseline() throws Exception {
        var mapper = JsonMapper.builder().build();
        var service = new MenuService();
        var cases = new ArrayList<Map<String, Object>>();
        for (String role : List.of("NONE", "OWNER", "ADMIN", "OPERATOR", "VIEWER")) {
            var projectRole = role.equals("NONE") ? null : ProjectRole.valueOf(role);
            for (boolean commercial : List.of(false, true)) {
                for (boolean reviewer : List.of(false, true)) {
                    var visible = rows(service.menusFor(projectRole, commercial, reviewer));
                    var scenario = new LinkedHashMap<String, Object>();
                    scenario.put("reviewer", reviewer);
                    scenario.put("names", visible.stream().map(row -> row.get("name")).toList());
                    scenario.put("role", role);
                    scenario.put("commercial", commercial);
                    scenario.put("permissions", service.permissionCodesFor(projectRole, commercial, reviewer));
                    cases.add(scenario);
                }
            }
        }
        var orderedDocument = new LinkedHashMap<String, Object>();
        orderedDocument.put("nodes", rows(MenuCatalog.all()));
        orderedDocument.put("cases", cases);
        var document = mapper.valueToTree(orderedDocument);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("things-link/pom.xml"))) root = root.getParent();
        assertThat(root).as("必须定位项目根目录").isNotNull();
        Path baseline = root.resolve("docs/delivery/verification/menu-catalog-baseline.json");
        if (Boolean.getBoolean("menu.contract.write")) {
            Files.writeString(baseline, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
        }
        assertThat(mapper.readTree(Files.readString(baseline)))
                .as("后端菜单/权限漂移：显式更新快照后必须同时通过前端比对").isEqualTo(document);
    }

    private static List<Map<String, Object>> rows(List<MenuItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        flatten(items, "", "", rows);
        rows.sort(Comparator.comparing(row -> row.get("name").toString()));
        assertThat(rows.stream().map(row -> row.get("name")).toList()).doesNotHaveDuplicates();
        return rows;
    }

    private static void flatten(List<MenuItem> items, String parent, String base, List<Map<String, Object>> rows) {
        for (var item : items) {
            String path = item.path().startsWith("/") ? item.path() : base + "/" + item.path();
            var row = new LinkedHashMap<String, Object>();
            row.put("name", item.name()); row.put("parent", parent); row.put("path", path);
            row.put("component", item.component()); row.put("title", item.meta().title());
            row.put("hidden", Boolean.TRUE.equals(item.meta().isHide()));
            row.put("fullPage", Boolean.TRUE.equals(item.meta().isFullPage()));
            row.put("fixedTab", Boolean.TRUE.equals(item.meta().fixedTab()));
            row.put("authCodes", item.meta().authList() == null ? List.of() : item.meta().authList().stream()
                    .map(MenuItem.AuthPoint::code).distinct().sorted().toList());
            rows.add(row);
            if (item.children() != null) flatten(item.children(), item.name(), path, rows);
        }
    }
}
