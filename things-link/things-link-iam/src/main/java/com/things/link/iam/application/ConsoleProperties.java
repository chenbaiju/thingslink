package com.things.link.iam.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台的对外地址，用于拼接邮件里的链接。
 *
 * <h2>为什么必须是配置项，不能从请求里取</h2>
 * 从 {@code Host} 头或 {@code X-Forwarded-Host} 拼链接是常见做法，也是常见漏洞：
 * 这两个头都由客户端控制。攻击者对着「忘记密码」接口发一个伪造 Host 的请求，
 * 受害者收到的重置链接就指向攻击者的域名 —— 点开即交出令牌。
 * 这类问题有个名字叫 主机请求头注入，修法只有一个：<b>链接的域名由服务端配置决定</b>。
 *
 * <p>当前生产调用方是 IAM 邮件适配器，配置归属 iam。若新增跨模块调用方，
 * 再评估公共配置归属；不因历史 S6 设想提前建立反向模块依赖。
 *
 * @param baseUrl 控制台根地址，<b>不带结尾斜杠</b>，例如 {@code https://console.example.com}。
 *                本地开发默认指向 Vite 开发服务器
 */
@ConfigurationProperties(prefix = "things-link.console")
public record ConsoleProperties(String baseUrl) {

    /**
     * 紧凑构造器：兜默认值并去掉结尾斜杠。
     *
     * <p>去斜杠不是洁癖：拼出来的 {@code https://x//verify-email} 在多数服务器上
     * 能正常跳转，但在部分反向代理与 CDN 上会 404，而这只在部署后才暴露。
     */
    public ConsoleProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:3006" : baseUrl;
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

}
