package com.things.link.support.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** MinIO 私有对象存储必须只在四项连接配置完整时进入应用上下文。 */
class MinioPrivateObjectStorageAssemblyTests {

    /** 只导入目标组件，避免其他自动配置掩盖条件装配结果。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MinioPrivateObjectStorage.class)
    static class StorageConfiguration {
    }

    /** 共用的最小上下文运行器。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class);

    /** 独立业务模块没有对象存储配置时必须正常启动且不装配适配器。 */
    @Test
    void doesNotAssembleWhenStoragePropertiesAreAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MinioPrivateObjectStorage.class);
            assertThat(context).doesNotHaveBean(PrivateObjectStorage.class);
        });
    }

    /** 四项连接配置完整时必须装配唯一的私有对象存储适配器。 */
    @Test
    void assemblesWhenAllStoragePropertiesArePresent() {
        runner.withPropertyValues(
                        "things-link.storage.internal-endpoint=http://127.0.0.1:19000",
                        "things-link.storage.external-endpoint=http://127.0.0.1:19001",
                        "things-link.storage.access-key=test-access-key",
                        "things-link.storage.secret-key=test-secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MinioPrivateObjectStorage.class);
                    assertThat(context).hasSingleBean(PrivateObjectStorage.class);
                });
    }
}
