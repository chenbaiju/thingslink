package com.things.link.support.tenant;

import com.things.link.support.observability.DatabaseAvailabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 周期探测两个物理数据库池，形成不依赖业务调用位置的整体停库信号。
 *
 * <p>必须直接探测物理池：若经过默认路由，只能证明 CONTROL，DATA 故障会继续被掩盖。
 * 每次只执行常量 {@code SELECT 1}，不读取业务表，也不建立租户上下文。</p>
 */
@DualPoolDatabaseProbe
public final class DatabaseAvailabilityProbe {

    /** 只在可用性转换时记录，避免数据库持续故障时刷屏。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseAvailabilityProbe.class);
    /** CONTROL 物理连接池。 */
    private final DataSource controlPool;
    /** DATA 物理连接池。 */
    private final DataSource dataPool;
    /** 低基数 0/1 可用性指标。 */
    private final DatabaseAvailabilityMetrics metrics;

    /**
     * @param controlPool CONTROL 物理池
     * @param dataPool DATA 物理池
     * @param metrics 数据库可用性指标
     */
    public DatabaseAvailabilityProbe(
            DataSource controlPool,
            DataSource dataPool,
            DatabaseAvailabilityMetrics metrics) {
        this.controlPool = controlPool;
        this.dataPool = dataPool;
        this.metrics = metrics;
    }

    /**
     * 启动后立即探测，随后每五秒执行；独占维护调度器的一个短任务，不占业务 Worker。
     *
     * <p>CONTROL 默认 500ms、DATA 默认 2s 获取超时，因此整体停库时一次轮询仍有界；告警
     * 持续 15 秒才 firing，吸收单次连接轮换抖动。</p>
     */
    @Scheduled(
            initialDelayString = "${things-link.database.availability-probe.initial-delay-millis:0}",
            fixedDelayString = "${things-link.database.availability-probe.fixed-delay-millis:5000}",
            scheduler = "maintenanceScheduler")
    public void probeAll() {
        probe("control", controlPool);
        probe("data", dataPool);
    }

    /**
     * 执行一次常量探测并更新状态；异常正文不进入指标标签。
     *
     * @param pool 固定池名
     * @param dataSource 对应物理池
     */
    private void probe(String pool, DataSource dataSource) {
        boolean available = selectOne(dataSource);
        if (!metrics.record(pool, available)) {
            return;
        }
        if (available) {
            LOGGER.info("数据库可用性恢复 pool={}", pool);
        } else {
            LOGGER.warn("数据库可用性探测失败 pool={}", pool);
        }
    }

    /**
     * 直接执行 SELECT 1；语句级一秒上限补充连接池获取超时，防止网络半开无限挂起。
     *
     * @param dataSource 目标物理池
     * @return 连接、执行和结果均成功时为 true
     */
    private static boolean selectOne(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(1);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (Exception exception) {
            return false;
        }
    }
}
