package com.things.link.support.cache;

/** 业务模块接收统一缓存失效事件的稳定端口。 */
public interface CacheInvalidationHandler {
    /**
     * 处理自己认识的资源事件，其他资源必须直接忽略。
     *
     * @param event 已验证的统一失效事件
     * @return 是否由本处理器接受
     */
    boolean handle(CacheInvalidationEvent event);
}
