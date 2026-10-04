package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppBrowserSessionReplacementRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** 受限SECURITY DEFINER入口只返回void，旧用户身份不暴露给调用方。 */
@Repository
public class JdbcAppBrowserSessionReplacementRepository implements AppBrowserSessionReplacementRepository {
    /** 复用原登录事务物理连接，不开第二条事务先撤销旧族。 */
    private final JdbcTemplate jdbc;
    /** 装配普通应用角色数据源。 */
    public JdbcAppBrowserSessionReplacementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 参数和真实写事务先验证；锁超时/死锁保留SQL首因并由外层整体回滚。 */
    @Override public void lockUsers(UUID targetUserId, byte[] oldRefreshHash) {
        if (targetUserId == null || oldRefreshHash != null && oldRefreshHash.length != 32
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("浏览器会话替换需要完整参数及原写事务");
        }
        jdbc.query("SELECT public.app_lock_browser_session_replacement(?, ?)", rs -> { }, targetUserId, oldRefreshHash);
    }
}
