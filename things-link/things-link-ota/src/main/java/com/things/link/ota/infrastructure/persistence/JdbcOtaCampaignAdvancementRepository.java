package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaCampaignAdvancementRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 仅调用固定受限函数，不接受客户端提供范围或写入完成统计。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaCampaignAdvancementRepository implements OtaCampaignAdvancementRepository {
    /** 当前真实事务连接。 */
    private final JdbcTemplate jdbc;

    /** 注入数据平面访问。 */
    public JdbcOtaCampaignAdvancementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@inheritDoc} */
    @Override public Optional<Candidate> candidate() {
        return jdbc.query("SELECT * FROM ota_campaign_advancement_candidate()", (rs, row) -> new Candidate(
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getLong("state_version"))).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public boolean advance(UUID project, UUID campaign, long revision, int expectedBatchNumber,
            UUID actor, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_campaign_advance(?,?,?,?,?,?)", Boolean.class,
                project, campaign, revision, expectedBatchNumber, actor, reason));
    }

    /** {@inheritDoc} */
    @Override public boolean complete(UUID project, UUID campaign, long revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_campaign_complete(?,?,?)", Boolean.class,
                project, campaign, revision));
    }
}
