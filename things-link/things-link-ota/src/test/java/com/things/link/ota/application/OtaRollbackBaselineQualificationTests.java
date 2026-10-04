package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.ota.domain.OtaTypeBaselineErrorCode;
import com.things.link.shared.error.BusinessException;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅验证helper范围与异常分类的纯接缝，真实数据库与类型锁由集成专项证明。 */
class OtaRollbackBaselineQualificationTests {
    /** 三轴测试租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 三轴测试项目。 */ private final UUID project = UUID.randomUUID();
    /** 三轴测试类型。 */ private final UUID type = UUID.randomUUID();
    /** 已绑定连接的查询接缝。 */ private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    /** 原父基线服务接缝。 */ private final OtaTypeBaselineService parents = mock(OtaTypeBaselineService.class);
    /** 缺省无扩展，与正式默认语义相同。 */ private final OtaRollbackBaselineSource source = new OtaRollbackBaselineSource("");
    /** 模拟事务资源身份，不声称实际PG事务。 */ private final DataSource dataSource = mock(DataSource.class);
    /** 被测异常分类。 */ private final OtaRollbackBaselineQualification qualification = new OtaRollbackBaselineQualification(jdbc, parents, source);

    /** 仅为范围凭证纯测试绑定当前线程资源。 */
    @BeforeEach void bindTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(mock(Connection.class)));
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenant.toString()), eq(project.toString()))).thenReturn(true);
    }
    /** 每次彻底清理线程事务接缝，避免污染后续用例。 */
    @AfterEach void clearTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clear();
    }
    /** 既有RLS不匹配必须原样拒绝，不能返回缺配置掩盖调用错误。 */
    @Test void rejectsWrongScopeBeforeConsultingParent() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenant.toString()), eq(project.toString()))).thenReturn(false);
        assertThatThrownBy(() -> qualification.requireCurrent(tenant, project, type)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(parents);
    }
    /** 数据库首因必须保留同一异常对象，不映射为缺资格。 */
    @Test void preservesInfrastructureFailure() {
        var failure = new DataAccessResourceFailureException("测试数据库断连");
        doThrow(failure).when(parents).lockRuntime(any(OtaRuntimeScope.class), eq(type));
        assertThatThrownBy(() -> qualification.requireCurrent(tenant, project, type)).isSameAs(failure);
    }
    /** 仅明确的父资格缺失分类可统一不可用，其他业务错误原样保留。 */
    @Test void mapsOnlyParentEligibilityFailures() {
        for (var code : new OtaTypeBaselineErrorCode[] {OtaTypeBaselineErrorCode.UNAVAILABLE,
                OtaTypeBaselineErrorCode.INVALID, OtaTypeBaselineErrorCode.NOT_FOUND}) {
            doThrow(new BusinessException(code)).when(parents).lockRuntime(any(OtaRuntimeScope.class), eq(type));
            assertThatThrownBy(() -> qualification.requireCurrent(tenant, project, type)).isInstanceOfSatisfying(
                    BusinessException.class, failure -> assertThat(failure.errorCode().code()).isEqualTo(70042));
        }
        var failure = new BusinessException(OtaTypeBaselineErrorCode.FORBIDDEN);
        doThrow(failure).when(parents).lockRuntime(any(OtaRuntimeScope.class), eq(type));
        assertThatThrownBy(() -> qualification.requireCurrent(tenant, project, type)).isSameAs(failure);
    }
    /** 缺少调用方事务绝不由helper自行授予后台身份。 */
    @Test void rejectsMissingTransactionBeforeConsultingParent() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> qualification.requireCurrent(tenant, project, type)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(parents);
    }
}
