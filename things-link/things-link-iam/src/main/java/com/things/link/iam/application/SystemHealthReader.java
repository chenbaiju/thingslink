package com.things.link.iam.application;

import java.util.List;

/**
 * 读取平台运行依赖健康状态的应用层端口。
 *
 * <p>控制台只需要低敏的名称与状态，不应依赖 Actuator 的实现类型，更不能把健康详情中的
 * 数据库地址或异常栈透出。基础设施适配器负责执行真实探针并在此边界完成脱敏。</p>
 */
public interface SystemHealthReader {

    /**
     * 读取当前进程已注册的真实健康探针。
     *
     * @return 固定白名单中的依赖状态；未注册的可选依赖不伪造成健康
     */
    List<DependencyHealth> read();

    /**
     * 单个运行依赖的脱敏状态。
     *
     * @param code 稳定机器标识
     * @param name 简体中文显示名
     * @param status 归一化状态
     */
    record DependencyHealth(String code, String name, String status) {
    }
}
