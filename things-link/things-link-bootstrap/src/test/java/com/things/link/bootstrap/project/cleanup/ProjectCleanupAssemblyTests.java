package com.things.link.bootstrap.project.cleanup;

import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.application.ProjectCleanupWorker;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR0092：正式Bootstrap开启开关时真实领域Bean齐备；共享测试库不自动执行清理。 */
@SpringBootTest(properties = {"things-link.project.cleanup.enabled=true",
        "things-link.project.cleanup.initial-delay-millis=3600000"})
class ProjectCleanupAssemblyTests extends AbstractIntegrationTest {
    /** 真实条件装配与DATA切面的定时入口。 */
    @Autowired private ProjectCleanupWorker worker;
    /** 实际扫描到的领域贡献器，不能用mock填补某个缺域。 */
    @Autowired private List<ProjectCleanupContributor> contributors;
    /** 实际事务批次注册表。 */
    @Autowired private ProjectCleanupBatchService batches;

    /** 所有14个生产贡献器唯一装配，DATA及事务代理均存在；测试仅装配不动共享候选。 */
    @Test
    void assemblesAllProductionContributorsWhenExplicitlyEnabled() {
        assertThat(contributors).hasSize(ProjectCleanupStage.values().length);
        assertThat(contributors.stream().map(ProjectCleanupContributor::stage).toList())
                .containsExactlyInAnyOrder(ProjectCleanupStage.values());
        batches.requireCompleteConfiguration();
        assertThat(AopUtils.isAopProxy(worker)).isTrue();
        assertThat(AopUtils.isAopProxy(batches)).isTrue();
    }
}
