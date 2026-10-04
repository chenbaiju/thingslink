package com.things.link.simulator.api.controller;

import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.simulator.application.DeviceSimulator;
import com.things.link.simulator.application.DeviceSimulator.SimulationStats;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 独立模拟器进程的本地控制端点。
 *
 * <p>该 Controller 不会进入 ThingsLink 管理 API 的 classpath，也不属于主 OpenAPI 契约。
 * 部署时只应把 8090 绑定到本机或测试网络，设备 Token 会随启动请求进入此工具进程。</p>
 */
@RestController
@RequestMapping("/simulations")
public class SimulatorController {

    /** 模拟器应用服务。 */
    private final DeviceSimulator simulator;

    /** 单分片最大设备数；HTTP 层 @Size(max=1000) 仍是硬上限，本值可由 A4-0 结论下调。 */
    private final int maxShardDevices;

    /**
     * 创建控制器。
     *
     * @param simulator 模拟器应用服务
     * @param maxShardDevices 每分片安全设备数（服务层校验）
     */
    public SimulatorController(DeviceSimulator simulator,
                               @Value("${simulator.shard.max-devices:1000}") int maxShardDevices) {
        this.simulator = simulator;
        // 配置错误必须 fail-fast：小于 1 或超过 HTTP 层硬上限 1000 都意味着分片口径被误解，而不是运行时才发现。
        if (maxShardDevices < 1 || maxShardDevices > 1000) {
            throw new IllegalStateException(
                    "simulator.shard.max-devices 必须在 1..1000 之间，实际 " + maxShardDevices);
        }
        this.maxShardDevices = maxShardDevices;
    }

    /**
     * 建立设备连接并开始周期上报。
     *
     * <p>超分片规模的请求在此被拒绝：10,000 台应由多个模拟器进程/节点分片，而不是一个无限大的单次请求。
     * {@code @Size(max=1000)} 是 HTTP 层硬保护，本校验是可配置的服务层软保护，两者职责不同。</p>
     *
     * @param request 模拟参数与设备凭据
     * @return 启动后的统计快照
     */
    @PostMapping("/start")
    public ResponseEntity<SimulationStats> start(@Valid @RequestBody SimulationRequest request) {
        if (request.devices().size() > maxShardDevices) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单分片最多 " + maxShardDevices + " 台设备，请求 " + request.devices().size()
                            + " 台；更大规模请用多进程/多节点分片");
        }
        simulator.start(request);
        return ResponseEntity.ok(simulator.stats());
    }

    /**
     * 停止全部设备并主动断开。
     *
     * @return 停止后的统计快照
     */
    @PostMapping("/stop")
    public ResponseEntity<SimulationStats> stop() {
        simulator.stop();
        return ResponseEntity.ok(simulator.stats());
    }

    /**
     * 强制当前批次全部设备异常断线并进入带抖动的生产重连流程。
     *
     * @return 故障注入后的即时统计；调用方继续轮询 stats 观察恢复分散度
     */
    @PostMapping("/reconnect-storm")
    public ResponseEntity<SimulationStats> reconnectStorm() {
        simulator.reconnectStorm();
        return ResponseEntity.ok(simulator.stats());
    }

    /**
     * 让当前批次每台在线设备立即补发一条属性报文。
     *
     * <p>该入口只控制真实 MQTT 设备连接，不接触后端或数据库；C4a 故障矩阵借此把单条业务
     * {@code messageId} 精确放入故障窗口。调用返回只代表 MQTT 发布已发起，PUBACK 仍须从 stats 与 manifest 对账。</p>
     *
     * @return 本次属性报文自己的业务 messageId
     */
    @PostMapping("/publish-once")
    public ResponseEntity<UUID> publishOnce() {
        return ResponseEntity.ok(simulator.publishOnce());
    }

    /**
     * 以当前唯一设备连接发布指定命令的成功终态。
     *
     * <p>数据库停机场景必须先经生产 API 受理命令，再在数据库不可用时送入回复；专用入口避免
     * 暴露可伪造任意 Topic/payload 的通用发布器。commandId 仍由服务层复核 UUIDv7。</p>
     *
     * @param commandId 已由平台受理的 UUIDv7 命令
     * @return 本次回复自己的业务 messageId
     */
    @PostMapping("/publish-command-reply/{commandId}")
    public ResponseEntity<UUID> publishCommandReply(@PathVariable UUID commandId) {
        return ResponseEntity.ok(simulator.publishCommandReply(commandId));
    }

    /**
     * 查询不含凭据的运行统计。
     *
     * @return 状态快照
     */
    @GetMapping("/stats")
    public ResponseEntity<SimulationStats> stats() {
        return ResponseEntity.ok(simulator.stats());
    }
}
