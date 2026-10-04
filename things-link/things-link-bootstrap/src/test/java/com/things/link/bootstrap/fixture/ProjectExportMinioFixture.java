package com.things.link.bootstrap.fixture;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * S12-P0-5g1 项目导出的真实私有 MinIO 测试夹具。
 *
 * <p>夹具固定使用部署清单中的服务端版本，并只暴露随机宿主端口。测试通过生产采用的 MinIO SDK
 * 执行桶和对象操作，不依赖宿主机 {@code mc}，从而让本地与 GitHub CI 具有相同的验收边界。
 */
public final class ProjectExportMinioFixture {

    /** 与 {@code deploy/.env.example} 一致的 MinIO 服务端版本。 */
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z");

    /** 测试专用访问账号；随机映射端口且随 Ryuk 回收，不进入生产配置。 */
    private static final String ACCESS_KEY = "export-test-access";

    /** MinIO 要求秘密至少八个字符，本值只存在于隔离测试容器。 */
    private static final String SECRET_KEY = "export-test-secret-2026";

    /** ADR0075 决策5固定的私有导出桶。 */
    public static final String BUCKET = "export";

    /**
     * 全测试类共享单个容器，避免每个对象失败分支都承担一次服务端冷启动。
     *
     * <p>数据隔离由每个测试独占的对象前缀承担；调用方必须在 {@code finally} 删除该前缀。
     */
    private static final GenericContainer<?> MINIO = new GenericContainer<>(IMAGE)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(9000)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        MINIO.start();
        ensureBucket(client());
    }

    /** 工具类不允许实例化。 */
    private ProjectExportMinioFixture() {
    }

    /**
     * 返回真实服务端的外部可访问 endpoint。
     *
     * @return 带随机宿主端口的 HTTP endpoint
     */
    public static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    /**
     * 返回测试访问账号。
     *
     * @return MinIO access key
     */
    public static String accessKey() {
        return ACCESS_KEY;
    }

    /**
     * 返回测试访问秘密。
     *
     * @return MinIO secret key
     */
    public static String secretKey() {
        return SECRET_KEY;
    }

    /**
     * 创建连接当前真实容器的客户端。
     *
     * @return 独立 MinIO 客户端
     */
    public static MinioClient client() {
        return MinioClient.builder()
                .endpoint(endpoint())
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
    }

    /**
     * 直接读取对象字节，用于校验上传后的 ZIP，而不是信任生产适配器回读结果。
     *
     * @param objectKey 对象键
     * @return 对象完整字节
     */
    public static byte[] readObject(String objectKey) {
        try (InputStream input = client().getObject(GetObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("读取项目导出测试对象失败: " + objectKey, exception);
        }
    }

    /**
     * 列出指定独占前缀下的对象键，供 fencing 与孤儿清理断言使用。
     *
     * @param prefix 测试独占前缀
     * @return 按服务端返回顺序收集的对象键
     */
    public static List<String> listObjectKeys(String prefix) {
        try {
            List<String> keys = new ArrayList<>();
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(BUCKET)
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                keys.add(result.get().objectName());
            }
            return keys;
        } catch (Exception exception) {
            throw new IllegalStateException("列出项目导出测试对象失败: " + prefix, exception);
        }
    }

    /**
     * 删除一个测试独占前缀，确保失败分支也不会污染共享 MinIO 容器。
     *
     * @param prefix 测试独占前缀
     */
    public static void deletePrefix(String prefix) {
        MinioClient client = client();
        for (String objectKey : listObjectKeys(prefix)) {
            try {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(BUCKET)
                        .object(objectKey)
                        .build());
            } catch (Exception exception) {
                throw new IllegalStateException("删除项目导出测试对象失败: " + objectKey, exception);
            }
        }
    }

    /**
     * 创建私有桶；MinIO 新桶默认拒绝匿名访问，最终测试再直接验证该事实。
     *
     * @param client 真实 MinIO 客户端
     */
    private static void ensureBucket(MinioClient client) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
