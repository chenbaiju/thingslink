package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.TenantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 基于 JDBC 的租户仓储实现。
 */
@Repository
public class JdbcTenantRepository implements TenantRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(UUID id, String name) {
        // tenant 表没有 RLS（主键就是租户本身，且登录前必须能查到它），
        // 所以这里不需要租户上下文 —— 注册时也确实还没有
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", id, name);
    }

}
