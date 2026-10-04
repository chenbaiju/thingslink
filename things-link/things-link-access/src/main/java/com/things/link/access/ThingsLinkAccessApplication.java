package com.things.link.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * ADR0215 的设备接入装配入口。业务组件按扫描白名单逐片接线；
 * 通过双进程资格前不得用于切流。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.things.link", excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AccessComponentFilter.class))
public class ThingsLinkAccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThingsLinkAccessApplication.class, args);
    }
}
