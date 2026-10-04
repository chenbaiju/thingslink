package com.things.link.dashboard.domain;

/**
 * 应用公开定位符与既有目录发生全局唯一碰撞。
 *
 * <p>该异常只表达数据库已经由{@code app_application_app_key_uk}仲裁的随机key碰撞，
 * 供应用服务在同一事务内生成新key后有界重试。其他唯一键、外键或连接故障不得映射为本异常。</p>
 */
public final class ApplicationKeyCollisionException extends RuntimeException {

    /** 创建不携带数据库约束细节的碰撞结果，避免把持久层诊断暴露给上层。 */
    public ApplicationKeyCollisionException() {
        super("应用公开定位符发生碰撞");
    }
}
