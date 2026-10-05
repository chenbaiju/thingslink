package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.*;
import com.things.link.assistant.domain.ProbeLedger.Attempt;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
/** 委托数据库原子函数管理共享并发槽；调用方负责事务及当前权限范围。 */
@Repository
public class JdbcProbeSlotRepository implements ProbeSlotRepository {
    private final JdbcTemplate jdbc;
    public JdbcProbeSlotRepository(JdbcTemplate j){jdbc=j;}
    /** 沿用接口定义的准入契约；数据库检查原始期限及用户、项目共享槽。{@inheritDoc} */
    @Override public String acquire(Attempt a,UUID token){return jdbc.queryForObject("SELECT assistant_probe_slot_acquire(?,?,?,?,?)",String.class,a.tenantId(),a.projectId(),a.createdBy(),a.id(),token);}
    /** 沿用接口定义的释放契约；数据库要求机会身份与槽令牌同时匹配。{@inheritDoc} */
    @Override public boolean release(Attempt a,UUID token){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT assistant_probe_slot_release(?,?,?,?,?)",Boolean.class,a.tenantId(),a.projectId(),a.createdBy(),a.id(),token));}
}
