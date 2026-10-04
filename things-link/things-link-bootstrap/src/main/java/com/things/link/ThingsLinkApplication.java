package com.things.link;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ThingsLink 后端服务启动类。
 *
 * <p>本类只负责装配 Spring 应用上下文，不承担任何业务职责。不要在这里放 Bean
 * 定义、配置属性或工具方法 —— 启动类一旦开始承载业务，多模块拆分时它会变成
 * 无法归属到任何模块的孤儿代码。
 *
 * <p>本类位于 {@code things-link-bootstrap} 模块，是原平台管理/API 进程的
 * {@link SpringBootApplication} 入口；ADR0215 另设设备接入装配入口。
 *
 * <p><b>包名必须是 {@code com.things.link} 而不是 {@code com.things.link.bootstrap}。</b>
 * Spring 的组件扫描从本类所在包开始向下递归，启动类必须位于所有业务模块包的
 * 公共父包上，否则 {@code iam}、{@code project} 等模块的 Bean 一个都扫不到 ——
 * 而报错是「找不到某个 Bean」，不会提示是扫描范围问题，很容易查错方向。
 * 这是架构文档 10.1 命名规范的启动类例外；外部应用通过 runtime 模块装配。
 *
 * <p>当前进程对应架构文档第 4 节「建议的可部署单元」中的 {@code platform-api}。
 * 设备接入层（{@code device-access}）必须独立部署，不要为了省事合并进本进程 ——
 * 长连接流量会拖垮管理 API。
 */
@SpringBootApplication
public class ThingsLinkApplication {

    /**
     * 进程入口。
     *
     * @param args 命令行参数，由 Spring Boot 解析为配置属性，
     *             例如 {@code --spring.profiles.active=local}
     */
    public static void main(String[] args) {
        // macOS 系统代理（如 VPN）会被 JVM 自动读取并注入为 socksProxyHost / http.proxyHost，
        // 导致本地 PostgreSQL JDBC 连接被路由到不存在的代理端口而失败（UnknownHostException）。
        // 必须在 Spring 启动前清除，因为 DataSource / Flyway 的初始化远早于任何 Bean。
        System.setProperty("socksProxyHost", "");
        System.setProperty("http.proxyHost", "");

        SpringApplication.run(ThingsLinkApplication.class, args);
    }

}
