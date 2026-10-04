package com.things.link.support.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 两种存储端口并存时独立装配，缺失连接配置不能生成默认成功实现。 */
class MinioVersionedPrivateObjectStorageAssemblyTests {
    /** 同时导入旧导出端口和新版本端口，发现类型歧义。 */
    @Configuration(proxyBeanMethods = false)
    @Import({MinioPrivateObjectStorage.class, MinioVersionedPrivateObjectStorage.class})
    static class StorageConfiguration { }

    /** 最小上下文不引入网络或其他自动配置。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class);

    /** 任一配置缺失时均不能装配版本适配器。 */
    @Test
    void refusesIncompleteConfiguration() {
        String[] properties = {
                "things-link.storage.internal-endpoint=http://127.0.0.1:19000",
                "things-link.storage.external-endpoint=http://127.0.0.1:19001",
                "things-link.storage.access-key=test-access",
                "things-link.storage.secret-key=test-secret"};
        for (int missing = 0; missing < properties.length; missing++) {
            String[] incomplete = new String[properties.length - 1];
            int index = 0;
            for (int present = 0; present < properties.length; present++) {
                if (present != missing) { incomplete[index++] = properties[present]; }
            }
            runner.withPropertyValues(incomplete).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(VersionedPrivateObjectStorage.class);
                assertThat(context).doesNotHaveBean(PrivateObjectStorage.class);
            });
        }
    }

    /** 配置完整时每个公开端口各有唯一Bean，上下文关闭同时收束自有资源。 */
    @Test
    void assemblesIndependentStoragePorts() {
        runner.withPropertyValues(
                "things-link.storage.internal-endpoint=http://127.0.0.1:19000",
                "things-link.storage.external-endpoint=http://127.0.0.1:19001",
                "things-link.storage.access-key=test-access",
                "things-link.storage.secret-key=test-secret").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VersionedPrivateObjectStorage.class);
                    assertThat(context).hasSingleBean(PrivateObjectStorage.class);
                    assertThat(context.getBean(VersionedPrivateObjectStorage.class))
                            .isNotSameAs(context.getBean(PrivateObjectStorage.class));
                });
    }
}
