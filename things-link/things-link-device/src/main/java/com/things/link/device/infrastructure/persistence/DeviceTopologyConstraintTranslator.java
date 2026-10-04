package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceTopologyBusyException;
import com.things.link.shared.error.BusinessException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;

import java.util.function.Supplier;

/**
 * ADR0058：只翻译即时拓扑角色守卫标记的错误，不改变其他数据库错误或全局 JDBC 翻译策略。
 * 通过服务器结构化约束名辨认来源，避免 SQL、用户字段或异常消息碰巧含有相同文本时被误分类。
 */
public final class DeviceTopologyConstraintTranslator {

    /** 新迁移写入服务器 CONSTRAINT 字段的固定标记；必须与 ADR0058 的 SQL 守卫一致。 */
    private static final String GUARD_CONSTRAINT = "dev_topo_roles_guard";

    /** 工具类没有实例状态，禁止创建实例以免误作独立事务边界。 */
    private DeviceTopologyConstraintTranslator() {
    }

    /**
     * 执行可能触发即时角色守卫的单次仓储写入，错误只转换一次并立即向外传播。
     *
     * @param operation 保持调用方已有事务的数据库写入
     * @param <T> 原写调用的返回类型
     * @return 未改变的写调用结果
     */
    public static <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (!(cause instanceof PSQLException postgresException)) {
                    continue;
                }
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                if (serverError == null || !GUARD_CONSTRAINT.equals(serverError.getConstraint())) {
                    continue;
                }
                if ("55P03".equals(postgresException.getSQLState())) {
                    // 数据面必须保留瞬时异常，不能在 PostgreSQL 已失败的事务内写业务拒绝回执。
                    throw new DeviceTopologyBusyException(exception);
                }
                if ("23514".equals(postgresException.getSQLState())) {
                    BusinessException conflict = new BusinessException(DeviceErrorCode.DEVICE_TOPOLOGY_ROLE_CONFLICT);
                    conflict.initCause(exception);
                    throw conflict;
                }
            }
            // 0A000 等未支持隔离级别和其他 SQLSTATE 原样传播，不能伪装成暂时锁忙或角色冲突。
            throw exception;
        }
    }
}
