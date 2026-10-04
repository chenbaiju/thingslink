package com.things.link.bootstrap.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * OpenAPI后端候选写入的测试期防绕过守卫。
 *
 * <p>本类型不依赖Spring或集成测试基类，使租约、canonical路径及符号链接反例可以作为纯单元测试执行。
 */
final class OpenApiGenerationWriteGuard {

    /** 全仓更新OpenAPI及消费端生成物的唯一命令。 */
    static final String GENERATION_COMMAND = "python3 scripts/generate-openapi-contracts.py";

    /** 工具类型不允许实例化。 */
    private OpenApiGenerationWriteGuard() {
    }

    /**
     * 从测试类实际加载目录推导仓库canonical契约，避免Maven CLI属性伪造比较基准。
     *
     * <p>测试类固定从Bootstrap模块的{@code target/test-classes}加载；若构建布局变化导致锚点不在
     * {@code target/test-classes}，本方法直接失败，不能静默接受另一个目标。
     *
     * @param anchor Bootstrap测试类锚点。
     * @return 仓库内{@code docs/openapi.json}路径。
     */
    static Path canonicalRepositoryOutput(Class<?> anchor) {
        try {
            Path classes = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!classes.endsWith(Path.of("target", "test-classes"))) {
                throw new IllegalStateException("无法从测试类加载位置确认OpenAPI canonical路径：" + classes);
            }
            Path module = classes.getParent().getParent();
            return module.resolve("../../docs/openapi.json").normalize();
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("无法解析OpenAPI测试类加载位置", exception);
        }
    }

    /**
     * 拒绝缺失租约令牌、令牌不匹配或试图直接覆盖canonical契约的写请求。
     *
     * <p>令牌证明当前Maven进程由根生成入口创建；临时目标限制确保真正发布仍由根入口在候选复核后完成。
     *
     * @param output 本次写入目标。
     * @param canonical 仓库canonical契约路径。
     * @param propertyToken Maven系统属性中的租约令牌。
     * @param environmentToken 子进程环境中的租约令牌。
     * @throws IOException 路径无法解析。
     */
    static void requireAuthorizedTemporaryWrite(
            Path output,
            Path canonical,
            String propertyToken,
            String environmentToken) throws IOException {
        if (propertyToken == null || propertyToken.isBlank()
                || environmentToken == null || environmentToken.isBlank()
                || !MessageDigest.isEqual(
                        propertyToken.getBytes(StandardCharsets.UTF_8),
                        environmentToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("OpenAPI写模式缺少有效内部租约；请执行唯一生成入口："
                    + GENERATION_COMMAND);
        }
        if (sameLocation(output, canonical)) {
            throw new IllegalStateException("OpenAPI测试禁止直接覆盖docs/openapi.json；请执行唯一生成入口："
                    + GENERATION_COMMAND);
        }
    }

    /**
     * 比较两个路径最终是否指向同一位置，包括经过符号链接父目录的尚未创建目标。
     *
     * @param left 第一个路径。
     * @param right 第二个路径。
     * @return 两条路径是否落在同一文件位置。
     * @throws IOException 既有路径无法解析。
     */
    private static boolean sameLocation(Path left, Path right) throws IOException {
        // 两端既存时由文件系统身份识别硬链接；未创建的暂存目标再走真实祖先解析。
        if (Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right)) {
            return true;
        }
        return resolveAgainstRealAncestor(left).equals(resolveAgainstRealAncestor(right));
    }

    /**
     * 解析最长既有祖先的真实路径，再拼回未创建后缀，避免符号链接父目录绕过canonical比较。
     *
     * @param path 待解析路径。
     * @return 消除既有符号链接后的绝对规范路径。
     * @throws IOException 真实祖先无法读取。
     */
    private static Path resolveAgainstRealAncestor(Path path) throws IOException {
        Path cursor = path.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(cursor)) {
            missing.addFirst(cursor.getFileName());
            cursor = cursor.getParent();
            if (cursor == null) {
                throw new IllegalStateException("无法解析OpenAPI输出路径：" + path);
            }
        }
        Path resolved = cursor.toRealPath();
        for (Path part : missing) {
            resolved = resolved.resolve(part);
        }
        return resolved.normalize();
    }
}
