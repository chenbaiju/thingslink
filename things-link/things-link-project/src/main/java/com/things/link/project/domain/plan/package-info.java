/**
 * 租户与项目模块 —— 商用套餐目录领域层：报价快照、维度、权益与仓储接口。
 *
 * <p>目录是「平台卖什么」的版本化事实，来自 S14-0 冻结记录（decisionVersion 1 /
 * {@code product-revision-1}）。数值维度一律是确定值：禁止用 {@code null} 表示不限，
 * 禁止用 {@code 0} 表示未知；未交付能力用 {@link com.things.link.project.domain.plan.PlanEntitlement}
 * 的 {@code DISABLED} 表达，与 {@code limit=0} 语义分离。
 *
 * <p>本包不依赖 Spring 或持久化技术，仓储接口在此定义、实现在 {@code infrastructure}。
 */
package com.things.link.project.domain.plan;
