package com.things.link.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证模拟器独立进程的 Spring 上下文可以完整装配。
 *
 * <p>{@link DeviceSimulator} 的唯一生产构造器依赖 {@code simulator.manifest.dir} 注入，曾因存在第二个构造器
 * 导致 Spring 报「No default constructor found」而在真实启动时失败、单元测试却全部通过。此用例把整条装配链
 * （Controller → DeviceSimulator → PahoMqttDeviceClientFactory → Jackson ObjectMapper）拉起来，
 * 作为该类启动期回归的兜底：单元测试不覆盖 Spring 注入，只有上下文加载才能抓到构造器歧义或 {@code @Value}
 * 缺失。</p>
 */
@SpringBootTest
class SimulatorApplicationTests {

    /** 上下文能装配即通过；业务行为由各应用层测试覆盖。 */
    @Test
    void contextLoads() {
        // 空方法：断言是 Spring 上下文加载本身。
    }
}
