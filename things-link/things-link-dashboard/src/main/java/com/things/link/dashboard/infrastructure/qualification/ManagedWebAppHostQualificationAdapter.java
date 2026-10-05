package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.application.publication.DashboardPublicHostContentPort;
import com.things.link.dashboard.application.publication.DashboardPublicHostUnavailableException;
import com.things.link.dashboard.application.publication.DashboardHostQualificationPort;
import com.things.link.dashboard.application.publication.DashboardHostDeploymentPort;
import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualification;
import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualificationPort;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.ComponentKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/** ADR0111受管宿主资格；每次核验实际归档及全部静态字节，不将候选描述符或mtime冒充资格。 */
@Component
public class ManagedWebAppHostQualificationAdapter implements DashboardHostQualificationPort, DashboardRegisteredHostQualificationPort, DashboardPublicHostContentPort {
    /** 日志只含固定分类和异常类型，不输出平台私有路径或文件正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(ManagedWebAppHostQualificationAdapter.class);
    /** 严格JSON不能先丢掉重复字段再验证封闭字段。 */
    private static final ObjectReader JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().reader();
    /** 制品解包总量上限，禁止zip bomb与无界资源读取。 */
    private static final int CONTENT_LIMIT = 16 * 1024 * 1024;
    /** Windows NIO提供者可能不公开稳定fileKey，需从原生文件句柄读取身份。 */
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** 首个受管发布没有动态latest或隐式版本升级。 */
    private final String registryDirectory;
    /** 当前选择的事务准入；显式目标盘点不消费此端口。 */
    private final DashboardHostDeploymentPort deployment;

    /** 包内文件竞态测试观察点，生产永远为空操作，不接收外部配置。 */
    private final java.util.function.Consumer<Path> afterRead;

    /** @param registryDirectory 平台受管根目录 @param deployment 同事务数据库准入 */
    @Autowired
    public ManagedWebAppHostQualificationAdapter(
            @Value("${things-link.dashboard.host.registry-directory:}") String registryDirectory,
            DashboardHostDeploymentPort deployment) {
        this.registryDirectory = registryDirectory;
        this.deployment = java.util.Objects.requireNonNull(deployment);
        this.afterRead = ignored -> { };
    }

    /** 显式目标的离线核验及旧版文件测试入口；生产装配必须使用带数据库准入的构造器。
     * @param registryDirectory 受管根目录
     */
    public ManagedWebAppHostQualificationAdapter(String registryDirectory) {
        this(registryDirectory, ignored -> { });
    }

    /** 包内文件竞态测试观察点，不扩展生产当前选择配置。
     * @param registryDirectory 受管目录 @param afterRead 读取观察点
     */
    ManagedWebAppHostQualificationAdapter(String registryDirectory, java.util.function.Consumer<Path> afterRead) {
        this.registryDirectory = registryDirectory;
        this.afterRead = afterRead;
        this.deployment = Optional::empty;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DashboardHostQualificationDescriptor> current() {
        try {
            var selected = deployment.admitCurrent();
            if (selected.isEmpty()) return findRegistered("1.0.0").map(DashboardRegisteredHostQualification::descriptor);
            var expected = selected.orElseThrow();
            return findRegistered(expected.hostVersion())
                    .filter(actual -> actual.artifactDigest().equals(expected.artifactDigest())
                            && actual.sourceDigest().equals(expected.sourceDigest()))
                    .map(DashboardRegisteredHostQualification::descriptor);
        } catch (RuntimeException failure) {
            LOG.warn("当前宿主准入不可用，类型={}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DashboardRegisteredHostQualification> findRegistered(String hostVersion) {
        return snapshot(hostVersion).map(VerifiedSnapshot::qualification);
    }

    /** 完整内容快照与资格共享唯一校验入口，不在成功后重开文件。 */
    private Optional<VerifiedSnapshot> snapshot(String hostVersion) {
        if (hostVersion == null || !Set.of("1.0.0", "1.1.0", "1.1.1").contains(hostVersion)) {
            return Optional.empty();
        }
        if (registryDirectory == null || registryDirectory.isBlank()) return Optional.empty();
        try {
            Path root = Path.of(registryDirectory);
            require(root.isAbsolute());
            for (Path segment : root) require(!segment.toString().equals(".") && !segment.toString().equals(".."));
            try (ManagedDirectory directory = ManagedDirectory.absolute(root, afterRead)) {
                VerifiedSnapshot descriptor = verify(directory, hostVersion);
                directory.verifyAll();
                return Optional.of(descriptor);
            }
        } catch (Exception failure) {
            // 首因的类型可诊断；不附带可能包含部署路径、源码片段的原始message/stacktrace。
            Exception diagnostic = new Exception("HOST_REGISTRY_INVALID:" + failure.getClass().getSimpleName());
            diagnostic.setStackTrace(failure.getStackTrace());
            LOG.warn("受管WebApp宿主资格核验失败", diagnostic);
            return Optional.empty();
        }
    }

    /** 同一次读取绑定descriptor/receipt/ZIP/release，不从多份可变latest拼装快照。 */
    private VerifiedSnapshot verify(ManagedDirectory root, String hostVersion) throws Exception {
        try (ManagedDirectory hosts = root.child("hosts"); ManagedDirectory host = hosts.child(hostVersion)) {
            host.exactChildren(Set.of("host-candidate.json", "source-receipt.json", "webapp-host.zip", "release"));
            byte[] descriptorBytes = host.read("host-candidate.json", 64 * 1024);
            byte[] receiptBytes = host.read("source-receipt.json", 2 * 1024);
            JsonNode descriptor = parse(descriptorBytes);
            fields(descriptor, "formatVersion", "hostVersion", "artifactDigestAlgorithm", "artifactDigest",
                    "supportedApplicationFormats", "supportedSchemas", "components", "resources", "manifest");
            require(text(descriptor, "formatVersion").equals("tc.webapp-host/v1"));
            require(text(descriptor, "hostVersion").equals(hostVersion));
            require(text(descriptor, "artifactDigestAlgorithm").equals("SHA-256"));
            String artifact = text(descriptor, "artifactDigest"); require(sha(artifact));
            singleton(descriptor.get("supportedApplicationFormats"), "tc.application/v1");
            singleton(descriptor.get("supportedSchemas"), "tc.dashboard/v1");
            JsonNode receipt = parse(receiptBytes);
            fields(receipt, "sourceDigest", "artifactDigest");
            require(sha(text(receipt, "sourceDigest")) && text(receipt, "artifactDigest").equals(artifact));
            byte[] archive = host.read("webapp-host.zip", 17 * 1024 * 1024);
            require(hash(archive).equals(artifact));
            Map<String, byte[]> files = archive(archive);
            try (ManagedDirectory release = host.child("release")) {
                Set<String> expected = new HashSet<>(files.keySet());
                expected.addAll(Set.of("host-candidate.json", "source-receipt.json", "precache.json"));
                require(release.tree().equals(expected));
                require(Arrays.equals(descriptorBytes, release.read("host-candidate.json", 64 * 1024)));
                require(Arrays.equals(receiptBytes, release.read("source-receipt.json", 2 * 1024)));
                for (var entry : files.entrySet()) {
                    require(Arrays.equals(entry.getValue(), release.read(entry.getKey(), 4 * 1024 * 1024)));
                }
                byte[] precacheBytes = release.read("precache.json", 64 * 1024);
                precache(parse(precacheBytes), artifact, files, hostVersion);
                manifest(descriptor.get("manifest"), parse(files.get("manifest.webmanifest")));
                mediaHeader(files.getOrDefault("assets/icon-192.png", new byte[0]), "png");
                mediaHeader(files.getOrDefault("assets/icon-512.png", new byte[0]), "png");
                Map<ComponentKind, String> components = components(descriptor.get("components"), hostVersion);
                Map<String, String> resources = resources(descriptor.get("resources"), files);
                var qualification = new DashboardRegisteredHostQualification(
                        new DashboardHostQualificationDescriptor("tc.webapp-host/v1", hostVersion,
                                Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"), components, resources),
                        artifact, text(receipt, "sourceDigest"));
                files.put("host-candidate.json", descriptorBytes); files.put("source-receipt.json", receiptBytes);
                files.put("precache.json", precacheBytes);
                return new VerifiedSnapshot(qualification, Map.copyOf(files));
            }
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<byte[]> readCurrent(String path) {
        try {
            var selected = deployment.admitCurrent();
            String version = selected.map(value -> value.hostVersion()).orElse("1.0.0");
            var actual = snapshot(version).orElseThrow(DashboardPublicHostUnavailableException::new);
            if (selected.isPresent() && (!selected.orElseThrow().artifactDigest().equals(actual.qualification().artifactDigest())
                    || !selected.orElseThrow().sourceDigest().equals(actual.qualification().sourceDigest()))) {
                throw new DashboardPublicHostUnavailableException();
            }
            return Optional.ofNullable(actual.files().get(path)).map(byte[]::clone);
        } catch (RuntimeException failure) { throw new DashboardPublicHostUnavailableException(); }
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<byte[]> readRelease(String digest, String path) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) return Optional.empty();
        for (var actual : publicSnapshots()) {
            if (actual.qualification().artifactDigest().equals(digest)) return Optional.ofNullable(actual.files().get(path)).map(byte[]::clone);
        }
        return Optional.empty();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<byte[]> readAsset(String path) {
        byte[] result = null;
        for (var actual : publicSnapshots()) {
            byte[] bytes = actual.files().get(path);
            if (bytes == null) continue;
            if (result != null && !Arrays.equals(result, bytes)) throw new DashboardPublicHostUnavailableException();
            result = bytes;
        }
        return Optional.ofNullable(result).map(byte[]::clone);
    }
    /** 最多三个固定注册；坏的已存在登记不被跳过为“不存在”。 */
    private java.util.List<VerifiedSnapshot> publicSnapshots() {
        if (registryDirectory == null || registryDirectory.isBlank()) throw new DashboardPublicHostUnavailableException();
        try {
            Path root = Path.of(registryDirectory);
            if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new DashboardPublicHostUnavailableException();
            var result = new java.util.ArrayList<VerifiedSnapshot>();
            for (String version : java.util.List.of("1.0.0", "1.1.0", "1.1.1")) {
                if (!Files.exists(root.resolve("hosts").resolve(version), LinkOption.NOFOLLOW_LINKS)) continue;
                result.add(snapshot(version).orElseThrow(DashboardPublicHostUnavailableException::new));
            }
            return result;
        } catch (RuntimeException failure) { throw new DashboardPublicHostUnavailableException(); }
    }
    /** 字节仅在私有快照中持有，所有对外响应均复制。 */
    private record VerifiedSnapshot(DashboardRegisteredHostQualification qualification, Map<String, byte[]> files) { }

    /** ZIP仅接受唯一工具写出的STORED普通文件；逐条同时检查中央目录、局部头、CRC与连续布局。 */
    private static Map<String, byte[]> archive(byte[] bytes) throws IOException {
        require(bytes.length >= 22);
        ByteBuffer zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        require(u32(zip, end) == 0x06054b50L && u16(zip, end + 20) == 0);
        require(u16(zip, end + 4) == 0 && u16(zip, end + 6) == 0);
        int count = u16(zip, end + 10);
        require(count > 0 && count <= 128 && count == u16(zip, end + 8));
        long centralSize = u32(zip, end + 12), centralOffset = u32(zip, end + 16);
        require(centralOffset + centralSize == end);
        int position = Math.toIntExact(centralOffset), local = 0, total = 0;
        Map<String, byte[]> files = new HashMap<>();
        for (int index = 0; index < count; index++) {
            require(position >= 0 && position + 46 <= end && u32(zip, position) == 0x02014b50L);
            int nameSize = u16(zip, position + 28);
            int extraSize = u16(zip, position + 30), commentSize = u16(zip, position + 32);
            require(extraSize == 0 && commentSize == 0 && u16(zip, position + 34) == 0);
            require(u16(zip, position + 8) == 0 && u16(zip, position + 10) == 0);
            require((u32(zip, position + 38) >>> 16) == 0100644);
            long size = u32(zip, position + 24);
            require(size > 0 && size <= 4 * 1024 * 1024 && u32(zip, position + 20) == size);
            total = Math.addExact(total, (int) size); require(total <= CONTENT_LIMIT);
            require(position + 46 + nameSize <= end);
            String name = utf8(Arrays.copyOfRange(bytes, position + 46, position + 46 + nameSize));
            require(publicPath(name) && !files.containsKey(name));
            require(u32(zip, position + 42) == local && local + 30L + nameSize + size <= centralOffset);
            require(u32(zip, local) == 0x04034b50L && u16(zip, local + 6) == 0 && u16(zip, local + 8) == 0);
            require(u16(zip, local + 26) == nameSize && u16(zip, local + 28) == 0);
            require(u32(zip, local + 18) == size && u32(zip, local + 22) == size);
            require(u32(zip, local + 14) == u32(zip, position + 16));
            require(Arrays.equals(Arrays.copyOfRange(bytes, local + 30, local + 30 + nameSize),
                    Arrays.copyOfRange(bytes, position + 46, position + 46 + nameSize)));
            byte[] content = Arrays.copyOfRange(bytes, local + 30 + nameSize, local + 30 + nameSize + (int) size);
            CRC32 crc = new CRC32(); crc.update(content); require(crc.getValue() == u32(zip, position + 16));
            files.put(name, content);
            local += 30 + nameSize + (int) size;
            position += 46 + nameSize;
        }
        require(position == end && local == centralOffset);
        require(files.keySet().containsAll(Set.of("index.html", "manifest.webmanifest", "sw.js")));
        require(files.keySet().stream().anyMatch(name -> name.endsWith(".js") && name.startsWith("assets/")));
        return files;
    }

    /** 清单覆盖精确文件集合，不能藏额外代码或遗漏文件。 */
    private static void precache(JsonNode node, String artifact, Map<String, byte[]> files, String hostVersion) throws Exception {
        fields(node, "formatVersion", "hostVersion", "artifactDigest", "entries");
        require(text(node, "formatVersion").equals("tc.webapp-precache/v1")
                && text(node, "hostVersion").equals(hostVersion) && text(node, "artifactDigest").equals(artifact));
        JsonNode entries = node.get("entries"); require(entries.isArray() && entries.size() == files.size());
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : entries) {
            fields(entry, "path", "sha256", "byteLength");
            String path = text(entry, "path"); require(path.startsWith("/app/"));
            String name = path.substring(5); byte[] content = files.get(name);
            require(content != null && seen.add(name) && integer(entry.get("byteLength")) == content.length
                    && text(entry, "sha256").equals(hash(content)));
        }
    }

    /** 固定平台安装身份和公开图标，不允许租户应用改写manifest。 */
    private static void manifest(JsonNode descriptor, JsonNode actual) throws IOException {
        fields(descriptor, "id", "startUrl", "scope");
        require(text(descriptor, "id").equals("/app/") && text(descriptor, "startUrl").equals("/app/")
                && text(descriptor, "scope").equals("/app/"));
        fields(actual, "id", "start_url", "scope", "name", "short_name", "lang", "display",
                "background_color", "theme_color", "icons");
        require(text(actual, "id").equals("/app/") && text(actual, "start_url").equals("/app/")
                && text(actual, "scope").equals("/app/") && text(actual, "name").equals("ThingsLink 应用")
                && text(actual, "short_name").equals("ThingsLink") && text(actual, "lang").equals("zh-CN")
                && text(actual, "display").equals("standalone") && text(actual, "background_color").equals("#ffffff")
                && text(actual, "theme_color").equals("#2457da"));
        JsonNode icons = actual.get("icons"); require(icons.isArray() && icons.size() == 2);
        for (int index = 0; index < 2; index++) {
            String size = index == 0 ? "192" : "512";
            JsonNode icon = icons.get(index); fields(icon, "src", "sizes", "type", "purpose");
            require(text(icon, "src").equals("/app/assets/icon-" + size + ".png")
                    && text(icon, "sizes").equals(size + "x" + size)
                    && text(icon, "type").equals("image/png") && text(icon, "purpose").equals("any"));
        }
    }

    /** 按ADR0145的宿主/组件矩阵核验，不通过任意kind声明引入新能力。 */
    private static Map<ComponentKind, String> components(JsonNode entries, String hostVersion) throws IOException {
        String componentVersion = hostVersion.equals("1.0.0") ? "1.0.0" : "1.0.1";
        require(entries.isArray() && entries.size() == ComponentKind.values().length);
        Map<ComponentKind, String> result = new EnumMap<>(ComponentKind.class);
        for (JsonNode entry : entries) {
            fields(entry, "kind", "componentVersion");
            ComponentKind kind = ComponentKind.valueOf(text(entry, "kind"));
            require(text(entry, "componentVersion").equals(componentVersion) && result.put(kind, componentVersion) == null);
        }
        return result;
    }

    /** 摘要素材必须匹配归档实际字节及媒体文件头，不做任意图片解码。 */
    private static Map<String, String> resources(JsonNode entries, Map<String, byte[]> files) throws Exception {
        require(entries.isArray() && entries.size() <= 64);
        Map<String, String> result = new HashMap<>();
        for (JsonNode entry : entries) {
            fields(entry, "resourceId", "digestAlgorithm", "digest", "assetPath", "mediaType", "byteLength");
            String id = text(entry, "resourceId"), digest = text(entry, "digest");
            require(id.matches("[a-z][a-z0-9_]{0,63}") && sha(digest)
                    && text(entry, "digestAlgorithm").equals("SHA-256") && result.put(id, digest) == null);
            String path = text(entry, "assetPath"), media = text(entry, "mediaType");
            String extension = switch (media) { case "image/png" -> "png"; case "image/jpeg" -> "jpg";
                case "image/webp" -> "webp"; default -> throw new IOException("HOST_RESOURCE_TYPE"); };
            require(path.equals("assets/" + digest + "." + extension));
            byte[] bytes = files.get(path); require(bytes != null && bytes.length <= 1048576
                    && integer(entry.get("byteLength")) == bytes.length && hash(bytes).equals(digest));
            mediaHeader(bytes, extension);
        }
        return result;
    }

    /** 文件魔数只用于核已登记媒体类型，不将扩展名当证据。 */
    private static void mediaHeader(byte[] bytes, String extension) throws IOException {
        boolean valid = switch (extension) {
            case "png" -> bytes.length >= 24 && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "jpg" -> bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "webp" -> bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
        require(valid);
    }

    /** 只接受平台构建白名单公开路径，不解包到文件系统。 */
    private static boolean publicPath(String path) {
        return Set.of("index.html", "manifest.webmanifest", "sw.js").contains(path)
                || path.matches("assets/[A-Za-z0-9_-]+\\.(?:js|css|png|svg|woff2)");
    }
    /** 归档16位无符号字段。 */
    private static int u16(ByteBuffer buffer, int offset) { return Short.toUnsignedInt(buffer.getShort(offset)); }
    /** 归档32位无符号字段，防止有符号长度绕过预算。 */
    private static long u32(ByteBuffer buffer, int offset) { return Integer.toUnsignedLong(buffer.getInt(offset)); }
    /** 固定失败内容不会暴露文件路径。 */
    private static void require(boolean valid) throws IOException { if (!valid) throw new IOException("HOST_REGISTRY_INVALID"); }
    /** 按严格UTF-8拒绝替换字符式宽松解码。 */
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    /** 严格文档只解析一次，保留重复键和尾随值拒绝。 */
    private static JsonNode parse(byte[] bytes) throws IOException { return JSON.readTree(utf8(bytes)); }
    /** 闭集字段防止未交付扩展冒充资格。 */
    private static void fields(JsonNode node, String... expected) throws IOException {
        require(node != null && node.isObject() && node.size() == expected.length);
        for (String name : expected) require(node.has(name));
    }
    /** @return 必填字符串，null和数字不能隐式转换 */
    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field); require(value != null && value.isString()); return value.asString();
    }
    /** 单值数组不容许重复或额外协议。 */
    private static void singleton(JsonNode node, String expected) throws IOException {
        require(node != null && node.isArray() && node.size() == 1 && node.get(0).isString()
                && node.get(0).asString().equals(expected));
    }
    /** 数量必须JSON整数，不接受字符串或浮点替代。 */
    private static long integer(JsonNode node) throws IOException {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong()); return node.longValue();
    }
    /** 摘要字符串闭集。 */
    private static boolean sha(String text) { return text.matches("[a-f0-9]{64}"); }
    /** 真实字节SHA-256，不使用文件名或mtime。 */
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    /** 跨平台受管文件核验；信任平台独占、不覆盖注册目录，不宣称抵御管理员瞬时替换再还原。 */
    private static final class ManagedDirectory implements AutoCloseable {
        /** 已核验绝对目录，不做normalize掩盖来源。 */
        private final Path path;
        /** 同一次核验共享所有已观察目录、文件和集合身份。 */
        private final Snapshot snapshot;
        /** @param path 真实目录 @param snapshot 本次核验记录 */
        private ManagedDirectory(Path path, Snapshot snapshot) { this.path = path; this.snapshot = snapshot; }

        /** 从文件系统根逐级NOFOLLOW记录祖先，macOS不依赖缺失的SecureDirectoryStream。 */
        static ManagedDirectory absolute(Path path, java.util.function.Consumer<Path> afterRead) throws IOException {
            Snapshot snapshot = new Snapshot(afterRead);
            Path current = path.getRoot(); snapshot.observe(current, true);
            for (Path segment : path) { current = current.resolve(segment); snapshot.observe(current, true); }
            snapshot.verify(); return new ManagedDirectory(path, snapshot);
        }
        /** 单段子目录前后复核所有祖先，普通持久替换立即失败关闭。 */
        ManagedDirectory child(String name) throws IOException {
            segment(name); snapshot.verify();
            Path child = path.resolve(name); snapshot.observe(child, true); snapshot.verify();
            return new ManagedDirectory(child, snapshot);
        }
        /** 最终文件NOFOLLOW打开并有界读取，读取前后校验所有已观察身份。 */
        byte[] read(String name, int maximum) throws IOException {
            int slash = name.indexOf('/');
            if (slash >= 0) {
                require(name.substring(0, slash).equals("assets") && name.indexOf('/', slash + 1) < 0);
                try (ManagedDirectory assets = child("assets")) { return assets.read(name.substring(slash + 1), maximum); }
            }
            segment(name); snapshot.verify();
            Path file = path.resolve(name); snapshot.observe(file, false); snapshot.verify();
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(maximum + 1);
                require(bytes.length > 0 && bytes.length <= maximum);
            }
            snapshot.afterRead.accept(file);
            snapshot.verify(); return bytes;
        }
        /** 根集合严格闭合，不无界收集未知目录。 */
        void exactChildren(Set<String> expected) throws IOException {
            require(snapshot.children(path, expected.size()).equals(expected));
        }
        /** release集合只允许assets一级目录，记录集合以供整批末再次复核。 */
        Set<String> tree() throws IOException {
            Set<String> files = new HashSet<>();
            for (String name : snapshot.children(path, 132)) {
                Path child = path.resolve(name);
                BasicFileAttributes attributes = Snapshot.attributes(child);
                if (attributes.isDirectory()) {
                    require(name.equals("assets"));
                    try (ManagedDirectory assets = child(name)) {
                        for (String filename : snapshot.children(assets.path, 128)) {
                            snapshot.observe(assets.path.resolve(filename), false);
                            require(files.add("assets/" + filename) && files.size() <= 131);
                        }
                    }
                } else {
                    snapshot.observe(child, false);
                    require(files.add(name) && files.size() <= 131);
                }
            }
            snapshot.verify(); return files;
        }
        /** 整批返回前的最终祖先、文件身份及目录集合围栏。 */
        void verifyAll() throws IOException { snapshot.verify(); snapshot.verifyChildren(); snapshot.verify(); }
        /** 单段路径拒绝目录分隔符和路径跳转。 */
        private static void segment(String name) throws IOException {
            require(!name.isEmpty() && !name.equals(".") && !name.equals("..")
                    && !name.contains("/") && !name.contains("\\"));
        }
        /** 本实现没有持久目录句柄，作用域关闭不改注册内容。 */
        @Override public void close() { }
    }

    /** 文件身份事实仅在当前调用内存在，不缓存成功资格。 */
    private static final class Snapshot {
        /** 按祖先先于子项的顺序复验，避免通过已变成链接的中间目录继续读取。 */
        private final Map<Path, ObservedAttributes> identities = new java.util.LinkedHashMap<>();
        /** 本次已列举的严格目录集合，末次比较能够识别额外文件。 */
        private final Map<Path, Set<String>> collections = new java.util.LinkedHashMap<>();
        /** 生产为空操作的包内测试观察点。 */
        private final java.util.function.Consumer<Path> afterRead;
        /** @param afterRead 包内可控持久替换观察点 */
        Snapshot(java.util.function.Consumer<Path> afterRead) { this.afterRead = afterRead; }
        /** NOFOLLOW只读取本级属性；调用方必须已经先验证其祖先。 */
        static BasicFileAttributes attributes(Path path) throws IOException {
            BasicFileAttributes result = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(!result.isSymbolicLink());
            return result;
        }
        /** 时间戳不能证明同字节替换的身份；无稳定身份时拒绝资格。 */
        private static ObservedAttributes identified(Path path) throws IOException {
            BasicFileAttributes before = attributes(path);
            Object identity = before.fileKey();
            if (identity == null && WINDOWS) identity = WindowsHostFileIdentity.read(path);
            require(identity != null);
            BasicFileAttributes after = attributes(path);
            require(before.isDirectory() == after.isDirectory()
                    && before.isRegularFile() == after.isRegularFile()
                    && before.size() == after.size()
                    && before.lastModifiedTime().equals(after.lastModifiedTime()));
            return new ObservedAttributes(after, identity);
        }
        /** 记录首次身份，重复观察必须仍同文件，不能把替换当新基线。 */
        void observe(Path path, boolean directory) throws IOException {
            ObservedAttributes actual = identified(path);
            require(directory ? actual.attributes().isDirectory() : actual.attributes().isRegularFile());
            ObservedAttributes previous = identities.putIfAbsent(path, actual);
            if (previous != null) same(previous, actual);
        }
        /** 祖先先行，文件大小/修改时间补充识别原inode上的普通字节修改。 */
        void verify() throws IOException {
            for (var entry : identities.entrySet()) same(entry.getValue(), identified(entry.getKey()));
        }
        /** 目录变化由精确集合判断，不因共享系统祖先目录的正常mtime变化拒绝注册。 */
        private static void same(ObservedAttributes before, ObservedAttributes after) throws IOException {
            var first = before.attributes(); var second = after.attributes();
            require(before.identity().equals(after.identity())
                    && first.isDirectory() == second.isDirectory()
                    && first.isRegularFile() == second.isRegularFile());
            if (first.isRegularFile()) require(first.size() == second.size()
                    && first.lastModifiedTime().equals(second.lastModifiedTime()));
        }
        private record ObservedAttributes(BasicFileAttributes attributes, Object identity) { }
        /** 有界列举前后复验，最终仍需比对本次记录集合。 */
        Set<String> children(Path directory, int maximum) throws IOException {
            verify(); Set<String> result = list(directory, maximum); verify();
            Set<String> prior = collections.putIfAbsent(directory, Set.copyOf(result));
            if (prior != null) require(prior.equals(result));
            return result;
        }
        /** 每个实际目录最多固定数量，拒绝额外文件而不遍历全部攻击输入。 */
        private static Set<String> list(Path directory, int maximum) throws IOException {
            Set<String> result = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) require(result.add(entry.getFileName().toString()) && result.size() <= maximum);
            }
            return result;
        }
        /** 所有受管集合返回前再次检查，正常持续添加或删除不能漏过。 */
        void verifyChildren() throws IOException {
            for (var entry : collections.entrySet()) {
                verify(); require(entry.getValue().equals(list(entry.getKey(), entry.getValue().size()))); verify();
            }
        }
    }
}
