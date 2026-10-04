package com.things.link.support.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记必须绕过业务路由、分别探测 CONTROL/DATA 私有物理池的维护组件。
 *
 * <p>这是 G1-C4b-F2 双池可用性合同的窄范围架构标记，不是通用路由例外。普通后台任务仍必须使用
 * {@link DataPlaneDatabase}；新增双池探针应扩展既有组件，避免扩大可直连物理池的生产边界。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DualPoolDatabaseProbe {
}
