package com.things.link.assistant.application;

import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.telemetry.application.ConsoleHistoryEvidence;
import com.things.link.telemetry.application.ConsoleHistoryEvidenceService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

/** 只读历史工具编排；领域取证后以新事务复核当前项目成员及精确模型。 */
@Service
public class HistoryEvidenceService {
    private final ConsoleHistoryEvidenceService history;
    private final ConsoleDeviceEvidenceService devices;

    /**
     * 装配受权中性端口，不消费遥测领域对象或内部仓储。
     * @param history 遥测领域的有界历史证据读取端口
     * @param devices 设备领域的最终资格复核端口
     */
    public HistoryEvidenceService(ConsoleHistoryEvidenceService history, ConsoleDeviceEvidenceService devices) {
        this.history = history; this.devices = devices;
    }

    /**
     * 返回证据前再次确权；来源失败、撤权或模型变化均不得降级为空数据。
     * @param project 当前登录身份选择的项目
     * @param device 项目内设备标识
     * @param model 期望的不可变物模型版本
     * @param property 单个顶层数值属性键
     * @param from 包含起点，受平台套餐保留窗口约束
     * @param to 排他终点，总查询窗口最多一天
     * @return 原始中性证据，空点不代表正常，也不提供模型出站许可
     */
    public ConsoleHistoryEvidence read(UUID project, UUID device, UUID model, String property, Instant from, Instant to) {
        var result = history.read(project, device, model, property, from, to);
        if (result == null) throw new IllegalStateException("历史证据源返回无效结果");
        devices.revalidate(project, device, model);
        return result;
    }
}
