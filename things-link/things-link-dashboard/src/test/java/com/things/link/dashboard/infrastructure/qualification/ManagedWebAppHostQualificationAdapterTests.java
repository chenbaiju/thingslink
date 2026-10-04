package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.ComponentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

/** 纯文件系统/实际SHA256/ZIP反例；合成壳只测试校验协议，不冒充真实宿主产品资格。 */
class ManagedWebAppHostQualificationAdapterTests {
    /** 仅Windows允许按本机NIO能力跳过无法构造的反例。 */
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** 每例独占受管根，不读取开发者生产注册。 */
    @TempDir Path root;
    /** 用真实JSON序列化生成有界合成测试清单。 */
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 显式盘点与current选择独立，返回的摘要来自同次读取。 */
    @Test void explicitTargetsAreQualifiedWithoutChangingCurrent() throws Exception {
        for (String version : List.of("1.0.0", "1.1.0", "1.1.1")) {
            register(files(), false, version);
            var target = adapter().findRegistered(version).orElseThrow();
            assertThat(target.descriptor().hostVersion()).isEqualTo(version);
            assertThat(target.descriptor().componentVersions().values())
                    .containsOnly(version.equals("1.0.0") ? "1.0.0" : "1.0.1");
            assertThat(target.sourceDigest()).isEqualTo("a".repeat(64));
            assertThat(target.artifactDigest()).isEqualTo(hash(Files.readAllBytes(
                    root.resolve("hosts").resolve(version).resolve("webapp-host.zip"))));
        }
        assertThat(adapter().current().orElseThrow().hostVersion()).isEqualTo("1.0.0");
        Files.writeString(root.resolve("hosts/1.1.0/release/index.html"), "tampered target");
        assertThat(adapter().findRegistered("1.1.0")).isEmpty();
        assertThat(adapter().findRegistered("1.1.1")).isPresent();
        assertThat(adapter().current()).isPresent();
    }

    /** 数据库选择必须与同次文件核验的两个摘要匹配，存在旧版也不能回退。 */
    @Test void selectedIdentityMustMatchBothDigests() throws Exception {
        register(files(), false);
        register(files(), false, "1.1.0");
        var actual = adapter().findRegistered("1.1.0").orElseThrow();
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            var selected = new com.things.link.dashboard.application.publication.DashboardHostDeploymentSelection(
                    7, "1.1.0", mismatch == 1 ? "b".repeat(64) : actual.artifactDigest(),
                    mismatch == 2 ? "b".repeat(64) : actual.sourceDigest());
            var current = new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString(),
                    (com.things.link.dashboard.application.publication.DashboardHostDeploymentPort)
                            () -> java.util.Optional.of(selected)).current();
            if (mismatch == 0) assertThat(current.orElseThrow().hostVersion()).isEqualTo("1.1.0");
            else assertThat(current).isEmpty();
        }
        Files.writeString(root.resolve("hosts/1.1.0/release/index.html"), "tampered");
        var selected = new com.things.link.dashboard.application.publication.DashboardHostDeploymentSelection(
                7, "1.1.0", actual.artifactDigest(), actual.sourceDigest());
        assertThat(new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString(),
                (com.things.link.dashboard.application.publication.DashboardHostDeploymentPort)
                        () -> java.util.Optional.of(selected)).current()).isEmpty();
        assertThat(adapter().current()).isPresent();
    }

    /** 准入数据库异常不能降级旧宿主，显式离线核验仍与当前选择分离。 */
    @Test void admissionFailureDoesNotFallBackToLegacy() throws Exception {
        register(files(), false);
        var failing = new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString(),
                (com.things.link.dashboard.application.publication.DashboardHostDeploymentPort)
                        () -> { throw new IllegalStateException("unavailable"); });
        assertThat(failing.current()).isEmpty();
        assertThat(failing.findRegistered("1.0.0")).isPresent();
    }

    /** 同名资源不同字节不能成为immutable公共资源，各自精确摘要仍可只读定位。 */
    @Test void publicAssetsRejectCrossVersionNameCollision() throws Exception {
        var first = files(); var second = files();
        second.put("assets/main.js", "/* different bytes */".getBytes(StandardCharsets.UTF_8));
        register(first, false, "1.1.0"); register(second, false, "1.1.1");
        var registered = adapter().findRegistered("1.1.0").orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter().readAsset("assets/main.js"))
                .isInstanceOf(com.things.link.dashboard.application.publication.DashboardPublicHostUnavailableException.class);
        assertThat(adapter().readRelease(registered.artifactDigest(), "assets/main.js").orElseThrow()).isEqualTo(first.get("assets/main.js"));
        assertThat(adapter().readAsset("assets/icon-192.png")).isPresent();
    }

    /** 对外数组不能改写之后的实际读取；返回的是核验快照，不暴露ZIP或目录。 */
    @Test void publicBytesAreDefensivelyCopiedAndWhitelistBound() throws Exception {
        register(files(), false);
        byte[] first = adapter().readCurrent("index.html").orElseThrow(); first[0] = 0;
        assertThat(adapter().readCurrent("index.html").orElseThrow()[0]).isEqualTo((byte)'<');
        assertThat(adapter().readCurrent("webapp-host.zip")).isEmpty();
        assertThat(adapter().readCurrent("../host-candidate.json")).isEmpty();
    }

    /** 无目标或非法版本绝不能回退到存在的旧版。 */
    @Test void missingAndUntrustedTargetsNeverFallBack() throws Exception {
        register(files(), false);
        for (String version : java.util.Arrays.asList(null, "", " ", "1.1.0", "1.1.2", "../1.0.0", "/tmp", "1.0.0/")) {
            assertThat(adapter().findRegistered(version)).isEmpty();
        }
        assertThat(adapter().current()).isPresent();
    }

    /** 目录名称不能替代内部身份，显式选择也不接受旧组件冒充新能力。 */
    @Test void rejectsTargetVersionAndComponentMismatches() throws Exception {
        register(files(), false, "1.1.0");
        Path target = root.resolve("hosts/1.1.0");
        byte[] original = Files.readAllBytes(target.resolve("host-candidate.json"));
        for (String invalid : List.of(
                new String(original, StandardCharsets.UTF_8).replace("1.1.0", "1.1.1"),
                new String(original, StandardCharsets.UTF_8).replace("1.0.1", "1.0.0"))) {
            Files.writeString(target.resolve("host-candidate.json"), invalid);
            Files.writeString(target.resolve("release/host-candidate.json"), invalid);
            assertThat(adapter().findRegistered("1.1.0")).isEmpty();
        }
        Files.write(target.resolve("host-candidate.json"), original);
        Files.write(target.resolve("release/host-candidate.json"), original);
        Path listing = target.resolve("release/precache.json");
        Files.writeString(listing, Files.readString(listing).replace("1.1.0", "1.1.1"));
        assertThat(adapter().findRegistered("1.1.0")).isEmpty();
    }

    /** 新目标目录仍禁止通过链接指向另一受管版本。 */
    @Test void rejectsLinkedExplicitTarget() throws Exception {
        register(files(), false);
        createSymbolicLink(root.resolve("hosts/1.1.0"), host());
        assertThat(adapter().findRegistered("1.1.0")).isEmpty();
        assertThat(adapter().current()).isPresent();
    }

    /** 空配置与不存在登记都保持不可用。 */
    @Test void unconfiguredAndMissingStayUnavailable() {
        assertThat(new ManagedWebAppHostQualificationAdapter("").current()).isEmpty();
        assertThat(adapter().current()).isEmpty();
    }
    /** 同一合成包完整验证成功；随后即使保持文件名，坏字节也必须马上撤掉资格。 */
    @Test void verifiesEveryReadInsteadOfCachingSuccess() throws Exception {
        register(files(), false);
        assertThat(adapter().current()).isPresent();
        Files.writeString(host().resolve("release/index.html"), "tampered");
        assertThat(adapter().current()).isEmpty();
    }
    /** 坏摘要和未知文件不能进入已验证集合。 */
    @Test void rejectsArchiveTamperAndExtraReleaseFile() throws Exception {
        register(files(), false);
        Files.writeString(host().resolve("release/private.json"), "{}");
        assertThat(adapter().current()).isEmpty();
        Files.delete(host().resolve("release/private.json"));
        byte[] zip = Files.readAllBytes(host().resolve("webapp-host.zip")); zip[0] ^= 1;
        Files.write(host().resolve("webapp-host.zip"), zip);
        assertThat(adapter().current()).isEmpty();
    }
    /** 替换为内容相同的符号链接也不是受管普通文件。 */
    @Test void rejectsSymbolicLink() throws Exception {
        register(files(), false);
        Path target = root.resolve("external.html"); Files.writeString(target, "<html></html>");
        Path file = host().resolve("release/index.html"); Files.delete(file); createSymbolicLink(file, target);
        assertThat(adapter().current()).isEmpty();
    }
    /** 重复JSON字段即使值相同仍拒绝，不允许宽松解析丢弃注册歧义。 */
    @Test void rejectsDuplicateDescriptorFields() throws Exception {
        register(files(), false);
        Path descriptor = host().resolve("host-candidate.json");
        String bytes = Files.readString(descriptor).replaceFirst("\\{", "{\"hostVersion\":\"1.0.0\",");
        Files.writeString(descriptor, bytes); Files.writeString(host().resolve("release/host-candidate.json"), bytes);
        assertThat(adapter().current()).isEmpty();
    }
    /** 摘要一致仍不能接受压缩归档；限制必须作用于归档内容规则。 */
    @Test void rejectsCompressedArchiveEvenWithMatchingDigest() throws Exception {
        register(files(), true);
        assertThat(adapter().current()).isEmpty();
    }
    /** 输入白名单禁止任意源映射或嵌套路径，即使其摘要全部一致。 */
    @Test void rejectsUnknownArchiveEntry() throws Exception {
        Map<String, byte[]> files = files(); files.put("assets/source.map", new byte[] {1});
        register(files, false);
        assertThat(adapter().current()).isEmpty();
    }
    /** 同时更新ZIP和摘要也不能改变固定平台manifest。 */
    @Test void rejectsTenantManifestIdentity() throws Exception {
        Map<String, byte[]> files = files();
        files.put("manifest.webmanifest", new String(files.get("manifest.webmanifest"), StandardCharsets.UTF_8)
                .replace("/app/", "/tenant/").getBytes(StandardCharsets.UTF_8));
        register(files, false);
        assertThat(adapter().current()).isEmpty();
    }
    /** 双份旁置收据不能分别来自不同源码候选。 */
    @Test void rejectsReceiptMismatch() throws Exception {
        register(files(), false);
        Files.writeString(host().resolve("release/source-receipt.json"), "{}");
        assertThat(adapter().current()).isEmpty();
    }
    /** 描述符超限在完整读取之前拒绝。 */
    @Test void rejectsOversizedDescriptor() throws Exception {
        register(files(), false);
        Files.write(host().resolve("host-candidate.json"), new byte[65537]);
        assertThat(adapter().current()).isEmpty();
    }
    /** 父目录链接不能因叶子是真实目录而被接受。 */
    @Test void rejectsSymbolicLinkInConfiguredAncestor() throws Exception {
        register(files(), false);
        Path alias = root.resolve("alias"); createSymbolicLink(alias, root.toRealPath().resolve("hosts"));
        assertThat(new ManagedWebAppHostQualificationAdapter(alias.resolve("1.0.0").toString()).current()).isEmpty();
        // 建立结构完整的父别名：若只查root叶子，此路径原本可以取得同一有效登记。
        Path holder = root.resolve("holder"); Files.createDirectories(holder);
        Path registry = holder.resolve("registry"); Files.createDirectories(registry);
        Files.move(root.resolve("hosts"), registry.resolve("hosts"));
        Path parentAlias = root.resolve("parent-alias"); createSymbolicLink(parentAlias, holder.toRealPath());
        assertThat(new ManagedWebAppHostQualificationAdapter(parentAlias.resolve("registry").toString()).current()).isEmpty();
        assertThat(new ManagedWebAppHostQualificationAdapter(registry.toRealPath().toString()).current()).isPresent();
    }
    /** 显式相对路径和含..配置必须拒绝，不能normalize后掩盖来源。 */
    @Test void rejectsRelativeAndTraversalConfiguration() throws Exception {
        register(files(), false);
        Files.createDirectory(root.resolve("child"));
        assertThat(new ManagedWebAppHostQualificationAdapter(root.toRealPath() + "/child/..").current()).isEmpty();
        assertThat(new ManagedWebAppHostQualificationAdapter(".").current()).isEmpty();
    }
    /** 读过叶子后同字节持久换inode也必须失败，不依赖字节摘要恰好变化。 */
    @Test void rejectsPersistentFileReplacementDuringRead() throws Exception {
        register(files(), false);
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        var adapter = new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString(), file -> {
            if (!file.endsWith("index.html") || !replaced.compareAndSet(false, true)) return;
            try {
                byte[] bytes = Files.readAllBytes(file);
                var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
                Files.move(file, root.resolve("previous-index"));
                Files.write(file, bytes);
                Files.setLastModifiedTime(file, attributes.lastModifiedTime());
                if (WINDOWS) Files.setAttribute(file, "basic:creationTime", attributes.creationTime());
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        assertThat(adapter.current()).isEmpty();
        assertThat(replaced).isTrue();
    }
    /** 校验期间祖先被持久换成链接不能继续通过原完整制品。 */
    @Test void rejectsPersistentParentReplacementDuringRead() throws Exception {
        register(files(), false);
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        var adapter = new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString(), file -> {
            if (!file.endsWith("host-candidate.json") || !replaced.compareAndSet(false, true)) return;
            try {
                Path previous = root.resolve("previous-hosts");
                Files.move(root.resolve("hosts"), previous);
                createSymbolicLink(root.resolve("hosts"), previous.toRealPath());
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        assertThat(adapter.current()).isEmpty();
        assertThat(replaced).isTrue();
    }
    /** @return 本例固定目录适配器 */
    private ManagedWebAppHostQualificationAdapter adapter() {
        try { return new ManagedWebAppHostQualificationAdapter(root.toRealPath().toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    /** Windows未启用开发者模式或未提升权限时无法创建符号链接。 */
    private static void createSymbolicLink(Path link, Path target) throws java.io.IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException unavailable) {
            if (!WINDOWS) throw unavailable;
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    /** @return 不存在latest指针的受管版本路径 */
    private Path host() { return root.resolve("hosts/1.0.0"); }

    /** 构造普通测试文件；不会被用作D-145生产正向证据。 */
    private Map<String, byte[]> files() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("index.html", "<html></html>".getBytes(StandardCharsets.UTF_8));
        files.put("sw.js", "/* fixture */".getBytes(StandardCharsets.UTF_8));
        files.put("assets/main.js", "/* fixture */".getBytes(StandardCharsets.UTF_8));
        byte[] png = new byte[24]; System.arraycopy(new byte[] {(byte)137,80,78,71,13,10,26,10},0,png,0,8);
        files.put("assets/icon-192.png", png); files.put("assets/icon-512.png", png);
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("id", "/app/"); manifest.put("start_url", "/app/"); manifest.put("scope", "/app/");
        manifest.put("name", "ThingsLink 应用"); manifest.put("short_name", "ThingsLink"); manifest.put("lang", "zh-CN");
        manifest.put("display", "standalone"); manifest.put("background_color", "#ffffff"); manifest.put("theme_color", "#2457da");
        manifest.put("icons", List.of(Map.of("src", "/app/assets/icon-192.png", "sizes", "192x192", "type", "image/png", "purpose", "any"),
                Map.of("src", "/app/assets/icon-512.png", "sizes", "512x512", "type", "image/png", "purpose", "any")));
        files.put("manifest.webmanifest", mapper.writeValueAsBytes(manifest)); return files;
    }
    /** 完整登记合成归档及匹配清单，以便反例只改变目标条件。 */
    private void register(Map<String,byte[]> files, boolean compressed) throws Exception {
        register(files, compressed, "1.0.0");
    }

    /** 合成完整目标版本归档，仅证明读取边界。 */
    private void register(Map<String,byte[]> files, boolean compressed, String hostVersion) throws Exception {
        Path host = root.resolve("hosts").resolve(hostVersion);
        Path release = host.resolve("release"); Files.createDirectories(release.resolve("assets"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.US_ASCII)) {
            for (var entry : files.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                if (!compressed) {
                    CRC32 crc = new CRC32(); crc.update(entry.getValue()); item.setMethod(ZipEntry.STORED);
                    item.setSize(entry.getValue().length); item.setCompressedSize(entry.getValue().length); item.setCrc(crc.getValue());
                }
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        byte[] archive = output.toByteArray(); ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        // Java测试写包不设置Unix模式；按唯一Python构建器的普通文件权限补中央目录元数据。
        int position = buffer.getInt(archive.length - 6);
        for (int index = 0; index < files.size(); index++) {
            buffer.putInt(position + 38, 0100644 << 16);
            position += 46 + Short.toUnsignedInt(buffer.getShort(position + 28))
                    + Short.toUnsignedInt(buffer.getShort(position + 30)) + Short.toUnsignedInt(buffer.getShort(position + 32));
        }
        String digest = hash(archive);
        Map<String,Object> descriptor = new LinkedHashMap<>();
        descriptor.put("formatVersion", "tc.webapp-host/v1"); descriptor.put("hostVersion", hostVersion);
        descriptor.put("artifactDigestAlgorithm", "SHA-256"); descriptor.put("artifactDigest", digest);
        descriptor.put("supportedApplicationFormats", List.of("tc.application/v1")); descriptor.put("supportedSchemas", List.of("tc.dashboard/v1"));
        List<Map<String,String>> components = new ArrayList<>();
        for (ComponentKind kind : ComponentKind.values()) components.add(Map.of("kind", kind.name(), "componentVersion", hostVersion.equals("1.0.0") ? "1.0.0" : "1.0.1"));
        descriptor.put("components", components); descriptor.put("resources", List.of());
        descriptor.put("manifest", Map.of("id", "/app/", "startUrl", "/app/", "scope", "/app/"));
        byte[] descriptorBytes = mapper.writeValueAsBytes(descriptor);
        byte[] receipt = mapper.writeValueAsBytes(Map.of("sourceDigest", "a".repeat(64), "artifactDigest", digest));
        Files.write(host.resolve("host-candidate.json"), descriptorBytes);
        Files.write(host.resolve("source-receipt.json"), receipt); Files.write(host.resolve("webapp-host.zip"), archive);
        Files.write(release.resolve("host-candidate.json"), descriptorBytes); Files.write(release.resolve("source-receipt.json"), receipt);
        List<Map<String,Object>> entries = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Files.write(release.resolve(entry.getKey()), entry.getValue());
            entries.add(Map.of("path", "/app/" + entry.getKey(), "sha256", hash(entry.getValue()), "byteLength", entry.getValue().length));
        }
        Files.write(release.resolve("precache.json"), mapper.writeValueAsBytes(Map.of("formatVersion", "tc.webapp-precache/v1",
                "hostVersion", hostVersion, "artifactDigest", digest, "entries", entries)));
    }
    /** @return 实际字节摘要 */
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
