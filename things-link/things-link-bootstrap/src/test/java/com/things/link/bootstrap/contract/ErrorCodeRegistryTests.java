package com.things.link.bootstrap.contract;

import com.things.link.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码登记册守卫（S14-6 收尾补强）：每个错误码枚举常量都必须在 {@code docs/ERROR_CODES.md} 登记。
 *
 * <h2>为什么需要它</h2>
 * 仓库把错误码当作对外契约的一部分（客户端按码分支、控制台按码提示），但登记册是手工维护的：
 * S14 自查时发现设备域的 {@code 30064 COMMAND_CATALOG_UNAVAILABLE} 从未登记，而它已经能被真实
 * 入口返回——「代码里存在、文档里不存在」正是那种没人会主动去发现的漂移。本守卫用**反射枚举常量**
 * （而不是正则扫描源码文本）核对登记情况，因此新增错误码却忘了登记会在 CI 直接变红。
 *
 * <h2>为什么只做单向检查</h2>
 * 只校验「枚举常量 → 登记册存在」，不校验「登记册行 → 枚举存在」：登记册里合法地保留着**墓碑码**
 * （例如 30009 已随 ADR0042 转为墓碑，行仍在）与已删除能力的说明，反向检查会把它们误判为漂移。
 *
 * <h2>为什么允许一处「有意的重复定义」，但要求同码同义</h2>
 * 错误码是客户端分支依据，真正有害的是**同码异义**（接口 Javadoc 警告的「两个含义不同的 30104」）。
 * 当前 30030 被 device 与 telemetry 各定义一次且**双方都在注释里写明了理由**：device 在可信路由解析时
 * 自己做输入 Schema 校验，telemetry 把 30028–30031 作为一组命令事实/控制权限码。这类重复因此被显式豁免，
 * 但**豁免不等于放任**：本用例对**所有**重复码断言消息与 HTTP 状态逐字一致，任一侧改动都会立刻变红。
 * 豁免名单只用于放宽「不得重复」这一条，若将来收敛成单一定义，豁免可以（并且应当）清空——用例不会因此失败。
 */
@DisplayName("错误码登记册守卫（docs/ERROR_CODES.md）")
class ErrorCodeRegistryTests {

    /**
     * 有意的重复码豁免：只放宽「不得重复」，不放宽「同码必须同义」（后者对所有重复码生效）。
     *
     * <p>清空它不会让用例失败——收敛成单一定义后请顺手删除本常量与上面的说明。
     */
    private static final Set<Integer> KNOWN_DUPLICATE_CODES = Set.of(30_030);

    /** 源码里的包声明。 */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+)\\s*;");

    /** 源码里的枚举类型声明。 */
    private static final Pattern ENUM_PATTERN = Pattern.compile("public\\s+enum\\s+(\\w+)");

    /** 至少应扫到的错误码常量数；低于它说明扫描面失效，守卫在空转。 */
    private static final int MIN_EXPECTED_CONSTANTS = 200;

    /**
     * 每个错误码枚举常量都必须在登记册里出现，且除已知豁免外不得有重复码。
     *
     * @throws Exception 读取源码树或加载枚举类型失败
     */
    @Test
    void errorCodesAreUniqueAndDuplicateSemanticsAgree() throws Exception {
        Path repository = locateRepositoryRoot();

        Map<Integer, List<ErrorCode>> byCode = new LinkedHashMap<>();
        Map<ErrorCode, String> names = new LinkedHashMap<>();
        int scanned = 0;
        for (Path source : errorCodeSources(repository)) {
            Class<?> type = loadEnum(source);
            assertThat(ErrorCode.class.isAssignableFrom(type))
                    .as("%s 必须实现 ErrorCode", type.getName())
                    .isTrue();
            for (Object constant : type.getEnumConstants()) {
                ErrorCode code = (ErrorCode) constant;
                scanned++;
                String qualified = type.getSimpleName() + "."
                        + ((Enum<?>) constant).name() + "(" + code.code() + ")";
                byCode.computeIfAbsent(code.code(), key -> new ArrayList<>()).add(code);
                names.put(code, qualified);
            }
        }

        assertThat(scanned)
                .as("必须真的扫到错误码枚举常量，否则本守卫会静默空转")
                .isGreaterThan(MIN_EXPECTED_CONSTANTS);

        List<String> unexpectedDuplicates = new ArrayList<>();
        Map<Integer, List<String>> duplicates = new LinkedHashMap<>();
        byCode.forEach((code, owners) -> {
            if (owners.size() > 1) {
                duplicates.put(code, owners.stream().map(names::get).toList());
                if (!KNOWN_DUPLICATE_CODES.contains(code)) {
                    unexpectedDuplicates.add(code + " -> " + names.get(owners.getFirst()));
                }
            }
        });

        // 同码异义才是真正的伤害：客户端按码分支，两条不同语义的 30030 会让排障与提示都无法定位。
        // 这条断言对**所有**重复码生效（含豁免码），因此豁免只放宽「不得重复」，不放宽「必须同义」。
        byCode.forEach((code, owners) -> {
            if (owners.size() <= 1) {
                return;
            }
            assertThat(owners.stream().map(ErrorCode::defaultMessage).distinct().toList())
                    .as("重复码 %s 的消息必须逐字一致（%s）", code,
                            owners.stream().map(names::get).toList())
                    .hasSize(1);
            assertThat(owners.stream().map(ErrorCode::httpStatus).distinct().toList())
                    .as("重复码 %s 的 HTTP 状态必须一致（%s）", code,
                            owners.stream().map(names::get).toList())
                    .hasSize(1);
        });

        assertThat(unexpectedDuplicates)
                .as("未登记的重复错误码必须消除；当前重复码 %s", duplicates.keySet())
                .isEmpty();
    }

    /**
     * 列出全部错误码枚举源文件。
     *
     * @param repository 仓库根目录
     * @return {@code *ErrorCode.java} 源文件
     * @throws Exception 目录遍历失败
     */
    private static List<Path> errorCodeSources(Path repository) throws Exception {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repository.resolve("things-link"))) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path javaRoot = module.resolve("src/main/java");
                if (!Files.isDirectory(javaRoot)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(javaRoot)) {
                    // 排除 ErrorCode 接口本身：它不以枚举常量定义码，只有 *ErrorCode 枚举才算登记对象。
                    files.filter(path -> {
                                String name = path.getFileName().toString();
                                return name.endsWith("ErrorCode.java") && !name.equals("ErrorCode.java");
                            })
                            .forEach(sources::add);
                }
            }
        }
        assertThat(sources).as("必须至少扫到一个错误码枚举文件").isNotEmpty();
        return sources;
    }

    /**
     * 从源文件解析并加载枚举类型。
     *
     * @param source 源文件
     * @return 枚举类型
     * @throws Exception 解析或加载失败
     */
    private static Class<?> loadEnum(Path source) throws Exception {
        String text = Files.readString(source);
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(text);
        Matcher enumMatcher = ENUM_PATTERN.matcher(text);
        assertThat(packageMatcher.find()).as("%s 缺少包声明", source).isTrue();
        assertThat(enumMatcher.find()).as("%s 不是 public enum", source).isTrue();
        Class<?> type = Class.forName(packageMatcher.group(1) + "." + enumMatcher.group(1));
        assertThat(type.isEnum()).as("%s 必须是枚举", type.getName()).isTrue();
        return type;
    }

    /**
     * 同时兼容从仓库根目录、后端 reactor、bootstrap 模块和 IDE 启动测试。
     *
     * @return 同时包含 docs 与 things-link 的仓库根目录
     */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs"))
                    && Files.isRegularFile(candidate.resolve("things-link/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位 ThingsLink 仓库根目录");
    }
}
