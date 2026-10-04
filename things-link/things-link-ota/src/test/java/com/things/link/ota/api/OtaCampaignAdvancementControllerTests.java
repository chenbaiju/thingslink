package com.things.link.ota.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaCampaignAdvancementService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 保留重复键和严格字段类型，不能靠DTO反序列化丢弃错误请求。 */
class OtaCampaignAdvancementControllerTests {
    /** 非闭集、重复键及错误类型都不得触发控制事务。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"expectedRevision\":\"1\",\"expectedBatchNumber\":\"1\",\"reason\":\"ok\",\"extra\":true}",
            "{\"expectedRevision\":\"1\",\"expectedRevision\":\"2\",\"expectedBatchNumber\":\"1\",\"reason\":\"ok\"}",
            "{\"expectedRevision\":1,\"expectedBatchNumber\":\"1\",\"reason\":\"ok\"}",
            "{\"expectedRevision\":\"1\",\"expectedBatchNumber\":\"1\",\"reason\":null}",
            "{\"expectedRevision\":\"1\",\"expectedBatchNumber\":\"1\"}"
    })
    void invalidBodyNeverStartsTransaction(String body) {
        UUID project = UUID.randomUUID();
        var projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        var service = mock(OtaCampaignAdvancementService.class);
        var controller = new OtaCampaignAdvancementController(new OtaAuthorization(projects), service);
        assertThrows(BusinessException.class, () -> controller.advance(project, UUID.randomUUID(), "key",
                body.getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(service);
    }
}
