package com.things.link.support.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 文档的元信息。
 *
 * <p>架构文档 11.1：<b>OpenAPI 是唯一契约来源，前端类型与 SDK 由其生成，不手写。</b>
 *
 * <p>契约的具体内容（路径、参数、响应）来自各模块 Controller 的签名与 DTO，
 * 由 springdoc 在运行时提取；这里只补充无法从代码推断的整体信息。
 *
 * <h2>接口文档的暴露</h2>
 * {@code /v3/api-docs} 会暴露全部接口结构。这在开发期是必需的，在生产环境则是
 * 一份现成的攻击面地图。通过 {@code springdoc.api-docs.enabled} 控制，
 * 生产 profile 必须关闭 —— bootstrap 的 application-prod.yml 与启动守卫共同保证。
 *
 * <p>刻意不引入 Swagger UI（用的是 {@code springdoc-openapi-starter-webmvc-api}
 * 而非 {@code -ui}）：只需要 JSON 文档，UI 既是额外的攻击面，也会让生产包变大。
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {

    /**
     * 文档元信息。
     *
     * <p>{@code version} 是<b>API 契约的版本</b>，不是应用的构建版本 ——
     * 两者会分别演进：修一个 bug 会让构建版本前进，但契约没变。混为一谈会让
     * 客户端无法判断「这次升级需不需要改代码」。
     *
     * @return OpenAPI 文档定义
     */
    @Bean
    public OpenAPI thingsLinkOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ThingsLink HTTP 接口目录")
                .version("v1")
                .description("""
                        多租户 IoT PaaS 的 HTTP 接口目录，按模块功能分组。
                        覆盖管理面、App、开放集成、设备 HTTP 接入、WebSocket 握手及受控静态资源。
                        独立模拟器仅作工具目录登记，使用独立 servers，不在平台生产进程提供。
                        条件接口和暂停能力保留契约，不表示已启用、已公开部署或取得验收资格。
                        框架健康、指标及运维索引另列运行分组；文档自身和内部错误转发沿框架合同。
                        生产必须关闭 springdoc 文档，设备身份和非 JSON 响应不能套用管理面合同。

                        约定（架构文档 11.1）：
                        - 路径形如 /api/v1/projects/{projectId}/...
                        - 适用的业务写支持 Idempotency-Key；认证及明确只读POST不使用公共幂等缓存
                        - 管理面错误响应统一为 { code, message, traceId, details }，\
                        HTTP 状态码表达语义类别，业务错误码表达具体原因
                        - 管理面列表按各接口合同使用游标分页 { items, nextCursor, hasMore }，\
                        海量日志与时序点禁止深度 offset
                        - 时间一律 RFC3339 UTC 字符串，不返回已本地化的时间文本
                        """)
                // OpenAPI 的 license 字段描述代码许可，不承担“是否已通过商用发布门禁”的产品状态语义。
                // Apache-2.0 第 6 条不授予 ThingsLink 参考品牌商标权，必须与根 README 的无隶属声明同时保留。
                .license(new License()
                        .name("Apache License 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }

    /**
     * OpenAPI 服务地址修正器。
     *
     * <p>springdoc 会在生成阶段按当前 HTTP 请求补一个 {@code servers}，MockMvc 测试里
     * 就会变成 {@code http://localhost}。这不是本项目的真实访问约定：开发环境走
     * Vite 代理，生产按架构文档 11.2 使用控制台与 API 同站部署。因此契约必须固定为
     * 同源根路径，否则将来一旦生成客户端，客户端可能直接打到错误地址。
     *
     * @return OpenAPI 生成后的服务地址修正器
     */
    @Bean
    public OpenApiCustomizer sameOriginServerCustomizer() {
        return openApi -> openApi.setServers(List.of(new Server()
                .url("/")
                .description("同源 API 根路径")));
    }

    /**
     * springdoc 2.8.6按Set迭代customizer，不应用@Order；全部bean建立后显式固定最终阶段。
     * 不重复执行模块customizer，后续新模块同样先生成领域Schema再统一校准。
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton globalStructureLast(
            org.springdoc.core.customizers.SpringDocCustomizers customizers,
            GlobalStructureCustomizer global) {
        return () -> customizers.getOpenApiCustomizers().ifPresent(all -> {
            all.remove(global);
            all.add(global);
        });
    }

}
