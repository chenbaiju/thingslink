package com.things.link.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 设备模拟器独立进程入口。
 *
 * <p>架构文档 10.2 与 13.4 将模拟器定义为工具模块，因此它拥有独立启动入口，
 * 不能由生产主服务装配。这样模拟压测失控时最多影响工具进程，不会耗尽管理 API 的线程和内存。</p>
 */
@SpringBootApplication
public class SimulatorApplication {

    /**
     * 启动独立模拟器控制服务。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
