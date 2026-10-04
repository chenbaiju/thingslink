/**
 * 横切基础类型：ID 生成、错误码、事件信封、租户上下文、游标分页。
 *
 * <p><b>本模块不得依赖 Spring 的任何东西</b>（架构文档 10.2），该约束由
 * {@code things-link-shared/pom.xml} 中的 banned-dependencies 规则强制。
 * 所有模块都依赖 shared，一旦它引入 Spring，全部模块都会传递性地拿到，
 * 分层约束将当场失效。需要 Spring 装配的横切能力放 {@code things-link-support}。
 *
 * <p>本模块不分 api/application/domain/infrastructure 四层 —— 它不是业务模块，
 * 没有领域可分。内部按用途分子包即可（{@code id}、{@code error}、{@code event}、
 * {@code tenant}、{@code page}）。
 *
 * <p>提供的稳定基础能力：
 * <ul>
 *   <li>UUIDv7 生成器。JDK 21 无内置实现，需自实现或引库（决策 #4）。
 *       ID 类型是全表主键，绝对不可后改</li>
 *   <li>错误码与统一响应结构。错误码分段规则见架构文档 11.1，
 *       新增前必须先登记进 {@code docs/ERROR_CODES.md}</li>
 *   <li>设备报文、原始上行与标准上行三层消息契约（架构文档第 5 节）</li>
 *   <li>{@code TenantContext}：从 JWT 解析并绑定到 ThreadLocal，配合 Filter 清理</li>
 *   <li>游标分页的编解码。海量日志与时序点禁止深度 offset 分页</li>
 * </ul>
 */
package com.things.link.shared;
