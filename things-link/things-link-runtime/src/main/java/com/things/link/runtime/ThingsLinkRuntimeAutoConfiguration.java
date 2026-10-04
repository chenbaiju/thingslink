package com.things.link.runtime;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 平台持有的完整业务装配入口。外部应用只选择是否启用，不能自行扫描内部包。
 * 首版保持与 bootstrap 相同的全量组件范围；模块裁剪须另行设计和验证。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "things-link.runtime", name = "embedded", havingValue = "true")
@ComponentScan(basePackages = "com.things.link")
public class ThingsLinkRuntimeAutoConfiguration {
}
