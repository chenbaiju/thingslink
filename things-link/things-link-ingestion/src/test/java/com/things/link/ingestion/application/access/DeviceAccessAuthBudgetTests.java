package com.things.link.ingestion.application.access;

import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 下的认证面预算：IP 与设备两道窗口、指数退避与成功清零。
 *
 * <p>窗口状态在 Redis，替身跑绿证明不了任何事；用例用随机 IP 与设备标识隔离，避免共享容器串场。</p>
 */
class DeviceAccessAuthBudgetTests extends AbstractKafkaIntegrationTest {

    /** 真实认证面预算组件。 */
    @Autowired
    private DeviceAccessAuthBudget budget;


    /** 同一 IP 超过每分钟上限后必须被拒。 */
    @Test
    void rejectsSourceIpBeyondMinuteBudget() {
        String ip = randomIp();
        String projectKey = "ax1f" + uniqueSuffix();

        for (int index = 0; index < 30; index++) {
            assertThat(budget.check(ip, projectKey, "device_" + index))
                    .as("第 " + (index + 1) + " 次来源 IP 认证应在预算内")
                    .isEqualTo(DeviceAccessAuthBudget.Decision.ALLOW);
        }

        assertThat(budget.check(ip, projectKey, "device_other"))
                .isEqualTo(DeviceAccessAuthBudget.Decision.RATE_LIMITED);
    }

    /** 同一设备超过每分钟定向上限后必须被拒，即使来源 IP 不断变化。 */
    @Test
    void rejectsDeviceIdentityBeyondTargetedBudget() {
        String projectKey = "ax1f" + uniqueSuffix();
        String deviceKey = "device_" + uniqueSuffix();

        for (int index = 0; index < 5; index++) {
            assertThat(budget.check(randomIp(), projectKey, deviceKey))
                    .as("第 " + (index + 1) + " 次定向认证应在预算内")
                    .isEqualTo(DeviceAccessAuthBudget.Decision.ALLOW);
        }

        assertThat(budget.check(randomIp(), projectKey, deviceKey))
                .as("换源地址不得换来新的定向额度")
                .isEqualTo(DeviceAccessAuthBudget.Decision.RATE_LIMITED);
    }

    /** 连续失败按 1s、2s 翻倍，成功一次立即清零。 */
    @Test
    void failureBackoffDoublesAndSuccessClears() {
        String projectKey = "ax1f" + uniqueSuffix();
        String deviceKey = "device_" + uniqueSuffix();

        assertThat(budget.recordFailure(projectKey, deviceKey)).isEqualTo(1_000L);
        assertThat(budget.check(randomIp(), projectKey, deviceKey))
                .isEqualTo(DeviceAccessAuthBudget.Decision.BACKOFF);
        assertThat(budget.recordFailure(projectKey, deviceKey)).isEqualTo(2_000L);

        budget.recordSuccess(projectKey, deviceKey);

        assertThat(budget.check(randomIp(), projectKey, deviceKey))
                .as("成功一次后必须清除退避")
                .isEqualTo(DeviceAccessAuthBudget.Decision.ALLOW);
    }

    /**
     * 生成用例内唯一短后缀。
     *
     * <p>不能用 UUIDv7 的前 8 个十六进制字符：那 32 位是毫秒时间戳高位，约 65 秒内生成的标识会完全相同，
     * 于是同一测试类里的不同用例会共用同一道窗口，把「允许」误判成「限流」。取尾部随机位。</p>
     *
     * @return 8 位随机十六进制后缀
     */
    private static String uniqueSuffix() {
        String hex = Uuid7.generate().toString().replace("-", "");
        return hex.substring(hex.length() - 8);
    }

    /**
     * 生成随机来源地址。
     *
     * <p>窗口状态在共享 Redis 内保留一分钟，因此地址空间必须足够大：只在一个 /24 里随机取点，会与本类
     * 其他用例刚耗尽的窗口撞车，把「允许」误报成「限流」。</p>
     *
     * @return 形如 10.x.y.z 的测试地址
     */
    private static String randomIp() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }
}
