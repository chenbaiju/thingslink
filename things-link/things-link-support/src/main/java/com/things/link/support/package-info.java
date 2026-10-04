/**
 * Spring 横切与技术适配：数据源、缓存、消息、对象存储、Web 层横切。
 *
 * <p><b>本模块不得依赖任何业务模块</b>（架构文档 10.2），该约束由
 * {@code things-link-support/pom.xml} 中的 banned-dependencies 规则强制。
 * 依赖方向永远是「业务模块 → support」；反向依赖一次，support 就成了
 * 循环依赖的中心。
 *
 * <p>命名说明：叫 support 而不是 infrastructure，是为了避开与业务模块内部
 * {@code com.things.link.<domain>.infrastructure} 分层包的撞名。二者是不同概念：
 * 前者是全局技术设施，后者是某个领域的持久化实现。
 *
 * <p>当前技术职责（具体装配及验证见对应实现）：
 * <ul>
 *   <li>数据源与 Flyway 装配；{@code ddl-auto} 固定为 validate</li>
 *   <li>统一异常处理与错误响应、{@code Idempotency-Key} 拦截器、
 *       traceId 贯穿（HTTP 入口 → MDC → 日志 → Kafka header → 消费端恢复）。
 *       最后一环最容易漏，漏了之后设备消息链路的排障等于瞎猜</li>
 *   <li>Redis 装配。注意淘汰策略是实例级的，限流令牌桶不能被淘汰，
 *       热影子缓存必须带显式 TTL（见 deploy/README.md）</li>
 *   <li>Kafka 装配与 traceId header 透传。业务 producer 仍必须把 deviceId 设为 record key</li>
 *   <li>S12-P0-5g1已提前交付通用私有对象存储端口与MinIO适配。internal/external endpoint
 *       必须是两个独立配置，合并会导致部署环境预签名URL 403；OTA固件校验、Range分发和
 *       signer等S13业务能力仍由S13独立交付</li>
 * </ul>
 */
package com.things.link.support;
