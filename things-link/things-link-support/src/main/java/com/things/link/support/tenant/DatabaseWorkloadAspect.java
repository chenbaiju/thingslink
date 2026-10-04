package com.things.link.support.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在事务拦截器之前建立 DATA 路由，并在任何退出路径恢复线程状态。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseWorkloadAspect {
    /**
     * 包围显式数据面入口。
     *
     * @param joinPoint 被调用入口
     * @return 原方法结果
     * @throws Throwable 原方法异常
     */
    @Around("@within(com.things.link.support.tenant.DataPlaneDatabase) || "
            + "@annotation(com.things.link.support.tenant.DataPlaneDatabase)")
    public Object routeToDataPool(ProceedingJoinPoint joinPoint) throws Throwable {
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return joinPoint.proceed();
        }
    }
}
