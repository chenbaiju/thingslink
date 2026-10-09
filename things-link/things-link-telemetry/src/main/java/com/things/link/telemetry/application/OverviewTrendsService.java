package com.things.link.telemetry.application;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.telemetry.api.dto.response.OverviewTrendsResponse;
import com.things.link.telemetry.api.dto.response.OverviewTrendsResponse.OverviewTrendChart;
import com.things.link.telemetry.api.dto.response.OverviewTrendsResponse.OverviewTrendSeries;
import com.things.link.telemetry.domain.OverviewTrendCatalog;
import com.things.link.telemetry.domain.OverviewTrendRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 受权读取完整小时窗口；缺失小时不以部分求和或零值掩盖。 */
@Service
public class OverviewTrendsService {
    /** 小时统计仓储。 */
    private final OverviewTrendRepository repository;
    /** 项目成员与真实归属查询。 */
    private final ProjectService projects;
    /** UTC 时钟。 */
    private final Clock clock;
    /** @param repository 统计仓储 @param projects 项目授权 @param clock UTC时钟 */
    public OverviewTrendsService(OverviewTrendRepository repository, ProjectService projects, Clock clock) {
        this.repository = repository; this.projects = projects; this.clock = clock;
    }
    /**
     * 查询十二类统计；先检查成员资格，跨租户成员使用项目归属租户，两个来源绝不混算。
     * @param projectId 当前受权项目 @param days 固定窗口：1、3、7、15、30天
     * @return 完整窗口曲线；没有样本时来源NONE且所有桶为空值
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public OverviewTrendsResponse get(UUID projectId, int days) {
        projects.requireRoleInProject(projectId);
        UUID tenantId = projects.requireProjectTenant(projectId);
        int step = switch (days) { case 1 -> 1; case 3 -> 3; case 7 -> 6; case 15 -> 12; case 30 -> 24;
            default -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER); };
        Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(days, ChronoUnit.DAYS);
        String source = repository.source(tenantId, projectId, from, to);
        int count = days * 24 / step;
        Map<String, List<Long>> values = new HashMap<>();
        if (!"NONE".equals(source)) {
            for (var bucket : repository.aggregate(tenantId, projectId, from, to, step, source)) {
                if (bucket.index() < 0 || bucket.index() >= count || bucket.samples() != step) continue;
                values.computeIfAbsent(bucket.metric(), k -> new ArrayList<>(Collections.nCopies(count, null)))
                        .set(bucket.index(), bucket.value());
            }
        }
        var charts = OverviewTrendCatalog.CHARTS.stream().map(c -> new OverviewTrendChart(
                c.key(), c.title(), c.unit(), c.gauge() ? "LAST" : "SUM", c.metrics().stream()
                .map(m -> new OverviewTrendSeries(m.key(), m.label(), Collections.unmodifiableList(
                        new ArrayList<>(values.getOrDefault(m.key(), Collections.nCopies(count, null)))))).toList())).toList();
        return new OverviewTrendsResponse(projectId, from, to, step, source, charts);
    }
}
