package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceCommitPort.Command;
import com.things.link.device.application.OtaDeviceCommitPort.ModelIdentity;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 提交端口结构错误必须先于数据库动作失败，不替代真实事务资格验证。 */
class OtaDeviceCommitValidationTests {
    /** 超安全整数或坏摘要拒绝在任何写入前。 */
    @Test
    void rejectsMalformedCounterAndArtifactBeforeDatabase() {
        var jdbc=mock(JdbcTemplate.class);var adapter=new JdbcOtaDeviceCommitAdapter(jdbc);
        assertThatThrownBy(()->adapter.apply(command(9007199254740992L,"a".repeat(64),"PG_JSONB_TEXT_V1_SHA256")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->adapter.apply(command(1,"not-a-digest","PG_JSONB_TEXT_V1_SHA256")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
    /** 未定义摘要算法不能被默认解释为已冻结算法。 */
    @Test
    void rejectsUnknownModelInterpretationBeforeDatabase() {
        var jdbc=mock(JdbcTemplate.class);var adapter=new JdbcOtaDeviceCommitAdapter(jdbc);
        assertThatThrownBy(()->adapter.apply(command(1,"a".repeat(64),"SHA256")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
    /** 最小明确元组只用于调用参数反例。 */
    private static Command command(long counter,String artifact,String algorithm) {
        var model=new ModelIdentity(UUID.randomUUID(),algorithm,"b".repeat(64),"TC_PROPERTY_COMPOSITE_V1");
        return new Command(new AuthenticatedDeviceIdentity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1),
                UUID.randomUUID(),model,model,UUID.randomUUID(),0,counter,artifact,true);
    }
}
