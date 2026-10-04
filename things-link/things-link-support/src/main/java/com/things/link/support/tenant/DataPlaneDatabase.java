package com.things.link.support.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要使用 DATA 数据库池的入口。
 *
 * <p>切面顺序早于事务 advice，确保 Spring 第一次获取并绑定连接之前已经选定物理池。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataPlaneDatabase {
}
