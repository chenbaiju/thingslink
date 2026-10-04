package com.things.link.issuer.infrastructure;

import com.things.link.issuer.application.ShcReviewerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 应用只调用受限数据库函数，不能直接授予或撤销审核人员。 */
@Repository
public class JdbcShcReviewerRepository implements ShcReviewerRepository {
    private final JdbcTemplate jdbc;

    public JdbcShcReviewerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean enabled(UUID actor, boolean holdLock) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.shc_reviewer_enabled(?, ?)", Boolean.class, actor, holdLock));
    }
}
