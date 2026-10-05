package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisSlotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class JdbcAnalysisSlotRepository implements AnalysisSlotRepository {
    private final JdbcTemplate jdbc;
    public JdbcAnalysisSlotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Admission acquire(AnalysisCall c, UUID token) {
        String result = jdbc.queryForObject("SELECT assistant_analysis_slot_acquire(?,?,?,?,?)", String.class,
                c.tenantId(),c.projectId(),c.createdBy(),c.id(),token);
        return Admission.valueOf(result);
    }
    @Override public boolean claim(AnalysisCall c, UUID token) { return action("claim",c,token); }
    @Override public boolean release(AnalysisCall c, UUID token) { return action("release",c,token); }
    private boolean action(String action, AnalysisCall c, UUID token) {
        // action仅来自本类固定方法，不能由业务输入组成SQL函数名。
        String sql = action.equals("claim") ? "SELECT assistant_analysis_slot_claim(?,?,?,?,?)"
                : "SELECT assistant_analysis_slot_release(?,?,?,?,?)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,c.tenantId(),c.projectId(),c.createdBy(),c.id(),token));
    }
    @Override public boolean wasClaimed(AnalysisCall c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT assistant_analysis_slot_claimed(?,?,?,?)",Boolean.class,
                c.tenantId(),c.projectId(),c.createdBy(),c.id()));
    }
    @Override public int expire(UUID tenant, UUID project) {
        return jdbc.queryForObject("SELECT assistant_analysis_slot_expire(?,?)",Integer.class,tenant,project);
    }
}
