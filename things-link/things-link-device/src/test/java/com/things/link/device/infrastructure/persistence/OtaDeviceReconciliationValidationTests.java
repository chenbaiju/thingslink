package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceReconciliationPort;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 新查询结构错误不能引发原提交端口写入或数据库读取。 */
class OtaDeviceReconciliationValidationTests {
    /** 缺少查询和非法安全整数拒绝在任何数据库动作前。 */
    @Test void rejectsMissingQueryAndUnsafeCounter(){
        var jdbc=mock(JdbcTemplate.class);var commits=mock(OtaDeviceCommitPort.class);var adapter=new JdbcOtaDeviceReconciliationAdapter(jdbc,commits);
        assertThatThrownBy(()->adapter.apply(command(null,1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->adapter.apply(command(UUID.randomUUID(),9007199254740992L))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc,commits);
    }
    /** 原permit必须明确，不能从query身份默认为同一个许可。 */
    @Test void rejectsMissingOriginalPermit(){
        var jdbc=mock(JdbcTemplate.class);var commits=mock(OtaDeviceCommitPort.class);var adapter=new JdbcOtaDeviceReconciliationAdapter(jdbc,commits);
        var c=command(UUID.randomUUID(),1);
        var bad=new OtaDeviceReconciliationPort.Command(c.identity(),c.deviceTypeId(),c.expectedSource(),c.target(),null,c.reconciliationId(),0,1,c.artifactSha256(),true);
        assertThatThrownBy(()->adapter.apply(bad)).isInstanceOf(NullPointerException.class);verifyNoInteractions(jdbc,commits);
    }
    /** 最小合法结构只用于纯参数反例。 */
    private static OtaDeviceReconciliationPort.Command command(UUID query,long counter){
        var model=new OtaDeviceCommitPort.ModelIdentity(UUID.randomUUID(),"PG_JSONB_TEXT_V1_SHA256","a".repeat(64),"TC_PROPERTY_COMPOSITE_V1");
        return new OtaDeviceReconciliationPort.Command(new AuthenticatedDeviceIdentity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1),
                UUID.randomUUID(),model,model,UUID.randomUUID(),query,0,counter,"c".repeat(64),true);
    }
}
