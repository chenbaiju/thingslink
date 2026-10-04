package com.things.link.bootstrap.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenAPI生成写入口的纯文件系统守卫反例。
 *
 * <p>X-02e要求旧Maven直写、伪造令牌与符号链接路径都不能覆盖canonical契约；本类不加载
 * Spring上下文或Testcontainers，使写治理可以在完整契约集成测试前快速失败。
 */
@DisplayName("OpenAPI生成写入口守卫")
class OpenApiGenerationWriteGuardTests {

    /** 每个反例独占的临时目录，避免触碰仓库契约与残留锁文件。 */
    @TempDir
    Path temporaryDirectory;

    /** 缺少属性令牌时必须拒绝写模式。 */
    @Test
    @DisplayName("缺少租约令牌时拒绝")
    void missingLeaseTokenIsRejected() {
        Path canonical = temporaryDirectory.resolve("docs/openapi.json");
        Path output = temporaryDirectory.resolve("staged/openapi.json");

        assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                output, canonical, null, "lease-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少有效内部租约");
    }

    /** 属性与环境令牌不一致时必须拒绝伪造的租约身份。 */
    @Test
    @DisplayName("租约令牌不一致时拒绝")
    void mismatchedLeaseTokensAreRejected() {
        Path canonical = temporaryDirectory.resolve("docs/openapi.json");
        Path output = temporaryDirectory.resolve("staged/openapi.json");

        assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                output, canonical, "lease-a", "lease-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少有效内部租约");
    }

    /** 即使令牌正确，canonical目标也只能由根生成入口发布阶段写入。 */
    @Test
    @DisplayName("正确令牌仍不能直写canonical契约")
    void canonicalOutputIsRejected() {
        Path canonical = temporaryDirectory.resolve("docs/openapi.json");

        assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                canonical, canonical, "lease-token", "lease-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止直接覆盖docs/openapi.json");
    }

    /** Maven CLI伪造的旧canonical属性不能改变由测试类加载位置推导的真实目标。 */
    @Test
    @DisplayName("CLI属性不能伪造canonical契约路径")
    void commandLinePropertyCannotOverrideCanonicalOutput() {
        String property = "openapi.canonical-output";
        String original = System.getProperty(property);
        System.setProperty(property, temporaryDirectory.resolve("fake-openapi.json").toString());
        try {
            Path canonical = OpenApiGenerationWriteGuard.canonicalRepositoryOutput(
                    OpenApiGenerationWriteGuardTests.class);

            assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                    canonical, canonical, "lease-token", "lease-token"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止直接覆盖docs/openapi.json");
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    /** 指向canonical父目录的符号链接不能把直写伪装成另一个临时路径。 */
    @Test
    @DisplayName("符号链接父目录不能绕过canonical守卫")
    void symbolicLinkParentCannotBypassCanonicalGuard() throws IOException {
        Path realDocs = Files.createDirectories(temporaryDirectory.resolve("real-docs"));
        Path linkedDocs = temporaryDirectory.resolve("linked-docs");
        try {
            Files.createSymbolicLink(linkedDocs, realDocs);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "当前文件系统不支持创建测试用符号链接：" + exception.getMessage());
        }

        Path canonical = realDocs.resolve("openapi.json");
        Path disguisedOutput = linkedDocs.resolve("openapi.json");
        assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                disguisedOutput, canonical, "lease-token", "lease-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止直接覆盖docs/openapi.json");
    }

    /** 指向canonical inode的硬链接不能把直写伪装成另一个暂存文件。 */
    @Test
    @DisplayName("硬链接不能绕过canonical守卫")
    void hardLinkCannotBypassCanonicalGuard() throws IOException {
        Path canonical = temporaryDirectory.resolve("docs/openapi.json");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "{}\n");
        Path disguisedOutput = temporaryDirectory.resolve("staged/openapi.json");
        Files.createDirectories(disguisedOutput.getParent());
        try {
            Files.createLink(disguisedOutput, canonical);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "当前文件系统不支持创建测试用硬链接：" + exception.getMessage());
        }

        assertThatThrownBy(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                disguisedOutput, canonical, "lease-token", "lease-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止直接覆盖docs/openapi.json");
    }

    /** 正确租约绑定的独立临时路径必须允许后端产出候选规范。 */
    @Test
    @DisplayName("正确租约允许独立临时路径")
    void authorizedTemporaryOutputIsAllowed() {
        Path canonical = temporaryDirectory.resolve("docs/openapi.json");
        Path output = temporaryDirectory.resolve("staged/openapi.json");

        assertThatCode(() -> OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                output, canonical, "lease-token", "lease-token"))
                .doesNotThrowAnyException();
    }
}
