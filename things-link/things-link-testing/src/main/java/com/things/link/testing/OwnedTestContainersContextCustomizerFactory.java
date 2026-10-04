package com.things.link.testing;

import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** 将专属容器所有者纳入Spring缓存键，禁止不同测试类共享将被类末销毁的上下文。 */
public final class OwnedTestContainersContextCustomizerFactory implements ContextCustomizerFactory {
    /** @param testClass 当前测试类 @param attributes 配置属性 @return 精确所有者缓存键 */
    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> attributes) {
        return testClass.getDeclaredAnnotation(OwnedTestContainers.class) == null ? null : new Owner(testClass);
    }

    /** 类身份作为值相等依据；不改变业务配置或初始化容器。 */
    private record Owner(Class<?> testClass) implements ContextCustomizer {
        /** @param context 测试上下文 @param configuration 缓存配置；此钩子不修改任何bean */
        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                MergedContextConfiguration configuration) {
            // 仅用record的equals/hashCode提供缓存隔离。
        }
    }
}
