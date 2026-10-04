package com.things.link.simulator.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 启动 MQTT 设备模拟请求。
 *
 * <p>每台设备必须携带自己的 Access Token，避免用一个共享凭据伪造“一机一密”演练。
 * Token 只保存在模拟器进程内存中，响应和日志均不得回显。</p>
 *
 * @param brokerUri MQTT Broker 地址，只接受 {@code tcp://} 或 {@code ssl://}
 * @param projectKey Topic 与 MQTT username 使用的项目标识
 * @param devices 待连接的设备及其一次性明文凭据
 * @param intervalSeconds 每台设备的属性上报周期（秒）
 * @param autoReplyCommands 是否自动以成功终态回复收到的下行命令；关闭后可演练平台超时和重试
 * @param runId 本轮资格运行标识；用于隔离 manifest 与跨分片聚合，不得含路径字符
 * @param shardId 当前分片标识；同一 runId 内唯一，不得含路径字符
 * @param propertiesPerReport 每条属性报文携带的属性数；A4-0/L0/L1/L2 标准模型固定为 10
 */
public record SimulationRequest(
        @NotBlank @Pattern(regexp = "^(tcp|ssl)://[^\\s]+$") String brokerUri,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String projectKey,
        @NotEmpty @Size(max = 1000) List<@Valid DeviceCredential> devices,
        @Min(1) @Max(3600) int intervalSeconds,
        boolean autoReplyCommands,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$") String runId,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$") String shardId,
        @Min(1) @Max(100) int propertiesPerReport) {

    /**
     * 保持旧的本地调用形状，并默认打开自动成功回复以让基础联调一启动就能形成命令闭环。
     *
     * <p>HTTP 请求应显式传递 {@code autoReplyCommands}：缺省 JSON boolean 为 {@code false}，
     * 有意用于演练设备不响应时的平台超时与重试路径。</p>
     *
     * @param brokerUri MQTT Broker 地址
     * @param projectKey 项目 Topic 段
     * @param devices 待启动设备
     * @param intervalSeconds 属性上报周期
     */
    public SimulationRequest(String brokerUri, String projectKey, List<DeviceCredential> devices, int intervalSeconds) {
        this(brokerUri, projectKey, devices, intervalSeconds, true, "local", "shard-000", 1);
    }

    /**
     * 兼容既有命令回复测试调用；默认身份只用于单进程本地测试，资格脚本必须显式给出唯一 runId/shardId。
     *
     * @param brokerUri MQTT Broker 地址
     * @param projectKey 项目 Topic 段
     * @param devices 待启动设备
     * @param intervalSeconds 属性上报周期
     * @param autoReplyCommands 是否自动回复命令
     */
    public SimulationRequest(String brokerUri, String projectKey, List<DeviceCredential> devices,
                             int intervalSeconds, boolean autoReplyCommands) {
        this(brokerUri, projectKey, devices, intervalSeconds, autoReplyCommands,
                "local", "shard-000", 1);
    }

    /**
     * 单台模拟设备的连接身份。
     *
     * @param deviceKey 项目内设备标识，也是 Topic 的设备段
     * @param accessToken 仅用于本次 MQTT 连接的设备 Access Token
     * @param gateway 是否按网关模拟：订阅 {@code down/config} 并批量上报子设备属性
     */
    public record DeviceCredential(
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String deviceKey,
            @NotBlank @Size(max = 512) String accessToken,
            boolean gateway) {
        /**
         * 兼容旧调用：默认按直连设备模拟。
         *
         * @param deviceKey 设备标识
         * @param accessToken 设备 Access Token
         */
        public DeviceCredential(String deviceKey, String accessToken) {
            this(deviceKey, accessToken, false);
        }
    }
}
