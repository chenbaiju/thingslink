package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceTopologyBusyException;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0058：拓扑守卫只按服务器结构化约束名分类，不能把其他故障或伪造消息当作业务竞争。 */
class DeviceTopologyConstraintTranslatorTests {

    /** 迁移与仓储的边界标记；测试独立声明以便发现生产常量误改导致的合同漂移。 */
    private static final String GUARD_CONSTRAINT = "dev_topo_roles_guard";

    /** 真实服务器锁忙标记必须保留瞬时异常语义与完整原始异常链，数据面才能继续重试。 */
    @Test
    void markedLockFailureBecomesTopologyBusyAndPreservesCause() {
        PSQLException postgres = serverFailure("55P03", GUARD_CONSTRAINT, "D111_TOPOLOGY_LOCK_BUSY");
        DataAccessException original = new UncategorizedSQLException("写拓扑", "INSERT INTO dev_topo", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isInstanceOfSatisfying(DeviceTopologyBusyException.class, translated -> {
            assertThat(translated).isInstanceOf(TransientDataAccessException.class);
            assertThat(translated.getCause()).isSameAs(original);
            assertThat(translated.getCause().getCause()).isSameAs(postgres);
        });
    }

    /** 只有本角色守卫的检查违反才转换30060，且不能丢失服务器诊断原因。 */
    @Test
    void markedRoleViolationBecomesBusinessConflictAndPreservesCause() {
        PSQLException postgres = serverFailure("23514", GUARD_CONSTRAINT, "D111_TOPOLOGY_ROLE_INVALID");
        DataAccessException original = new DataIntegrityViolationException("写类型", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isInstanceOfSatisfying(BusinessException.class, translated -> {
            assertThat(translated.errorCode()).isSameAs(DeviceErrorCode.DEVICE_TOPOLOGY_ROLE_CONFLICT);
            assertThat(translated.getCause()).isSameAs(original);
            assertThat(translated.getCause().getCause()).isSameAs(postgres);
        });
    }

    /** 相同SQLSTATE也可能来自其他表或约束，必须按原异常对象交给既有失败处理。 */
    @ParameterizedTest
    @ValueSource(strings = {"55P03", "23514"})
    void sameSqlStateFromAnotherConstraintPassesThroughUnchanged(String sqlState) {
        PSQLException postgres = serverFailure(sqlState, "unrelated_constraint", "其他数据库约束失败");
        DataAccessException original = new UncategorizedSQLException("其他写入", "UPDATE dev_type", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isSameAs(original);
    }

    /** 不支持的隔离级别不是稍后重试就会恢复的锁忙，带守卫标记也不能重分类。 */
    @Test
    void markedUnsupportedIsolationPassesThroughUnchanged() {
        PSQLException postgres = serverFailure("0A000", GUARD_CONSTRAINT, "D111_TOPOLOGY_REQUIRES_READ_COMMITTED");
        DataAccessException original = new UncategorizedSQLException("写类型", "UPDATE dev_type", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isSameAs(original);
    }

    /** 消息文本中的同名标记不等于服务器CONSTRAINT字段，不能被任意字符串诱导分类。 */
    @ParameterizedTest
    @ValueSource(strings = {"55P03", "23514"})
    void constraintNameInMessageWithoutStructuredMarkerPassesThroughUnchanged(String sqlState) {
        PSQLException postgres = serverFailure(sqlState, null, "伪造 CONSTRAINT=" + GUARD_CONSTRAINT);
        DataAccessException original = new UncategorizedSQLException(GUARD_CONSTRAINT, "UPDATE dev_type", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isSameAs(original);
    }

    /** 驱动本地产生的PSQLException没有服务器字段，不得因消息相似或字段为空而误分类或空指针。 */
    @Test
    void postgresExceptionWithoutServerFieldsPassesThroughUnchanged() {
        PSQLException postgres = new PSQLException(GUARD_CONSTRAINT, PSQLState.CHECK_VIOLATION);
        DataAccessException original = new UncategorizedSQLException("本地驱动失败", "UPDATE dev_type", postgres);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isSameAs(original);
    }

    /** 普通SQLException的SQLSTATE与文本都不能充当PostgreSQL结构化来源证明。 */
    @Test
    void nonPostgresExceptionWithSpoofedMarkerPassesThroughUnchanged() {
        SQLException sql = new SQLException("CONSTRAINT=" + GUARD_CONSTRAINT, "55P03");
        DataAccessException original = new UncategorizedSQLException("未知驱动失败", "UPDATE dev_type", sql);

        assertThatThrownBy(() -> DeviceTopologyConstraintTranslator.execute(() -> {
            throw original;
        })).isSameAs(original);
    }

    /**
     * 使用pgjdbc真实服务器字段解析器构造异常，避免Mockito将消息与结构化字段错误混为一谈。
     *
     * @param sqlState PostgreSQL错误分类
     * @param constraint 可空服务器约束字段，空时不发送该字段
     * @param message 与结构化约束标记独立的诊断消息
     * @return 带真实ServerErrorMessage的驱动异常
     */
    private static PSQLException serverFailure(String sqlState, String constraint, String message) {
        // PostgreSQL错误协议以单字符标识字段并用零字节分隔，n字段才是约束名，M仅是消息文本。
        StringBuilder fields = new StringBuilder("SERROR\0");
        fields.append('C').append(sqlState).append('\0');
        fields.append('M').append(message).append('\0');
        if (constraint != null) {
            fields.append('n').append(constraint).append('\0');
        }
        fields.append('\0');
        return new PSQLException(new ServerErrorMessage(fields.toString()));
    }
}
