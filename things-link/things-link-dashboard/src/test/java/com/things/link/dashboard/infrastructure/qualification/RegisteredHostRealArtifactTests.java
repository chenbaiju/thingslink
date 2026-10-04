package com.things.link.dashboard.infrastructure.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅在显式提供1a真实不可变注册目录时运行，不把合成ZIP冒充构建证据。 */
@EnabledIfSystemProperty(named = "thingslink.test.host-registry", matches = ".+")
class RegisteredHostRealArtifactTests {
    /** 独占副本，破坏性反例不写输入注册库。 */
    @TempDir Path copy;

    /** 两套真实字节通过同一生产适配器；篡改副本后目标立即失去资格。 */
    @Test void readsBothActualHostsAndDetectsChangedBytesWithoutActivating() throws Exception {
        Path source = Path.of(System.getProperty("thingslink.test.host-registry"));
        assertThat(source.isAbsolute()).isTrue();
        try (var files = Files.walk(source.resolve("hosts"))) {
            for (Path file : files.toList()) {
                assertThat(Files.isSymbolicLink(file)).isFalse();
                Path target = copy.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
        var adapter = new ManagedWebAppHostQualificationAdapter(copy.toRealPath().toString());
        var json = JsonMapper.builder().build();
        for (String version : List.of("1.1.0", "1.1.1")) {
            var qualified = adapter.findRegistered(version).orElseThrow();
            var receipt = json.readTree(Files.readString(copy.resolve("hosts").resolve(version)
                    .resolve("source-receipt.json")));
            assertThat(qualified.artifactDigest()).isEqualTo(receipt.get("artifactDigest").asString());
            assertThat(qualified.sourceDigest()).isEqualTo(receipt.get("sourceDigest").asString());
            assertThat(qualified.descriptor().hostVersion()).isEqualTo(version);
            assertThat(qualified.descriptor().componentVersions()).hasSize(10);
            assertThat(qualified.descriptor().componentVersions().values()).containsOnly("1.0.1");
        }
        assertThat(adapter.current()).isEmpty(); // 没有旧版，不隐式选已登记的新版本。
        Files.writeString(copy.resolve("hosts/1.1.0/release/index.html"), "changed bytes");
        assertThat(adapter.findRegistered("1.1.0")).isEmpty();
        assertThat(adapter.findRegistered("1.1.1")).isPresent();
    }
}
