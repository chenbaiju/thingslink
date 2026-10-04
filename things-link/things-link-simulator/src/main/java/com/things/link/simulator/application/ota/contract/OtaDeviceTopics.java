package com.things.link.simulator.application.ota.contract;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 设备侧 OTA MQTT Topic 常量与替换助手。
 *
 * <p><b>取值来源（不是自造）：</b>全部取自平台现有实现与已接受 ADR，逐条对应关系如下：
 * <ul>
 *   <li>{@code up/ota/download/request}：ADR0128「DIRECT 控制面仍只有 MQTT，Topic 为
 *       {@code tc/v1/{projectKey}/{deviceKey}/up/ota/download/request}」，与
 *       {@code OtaDownloadRequestUplinkHandler} 的消息类型 {@code ota/download/request} 一致。</li>
 *   <li>{@code up/ota/progress}：ADR0131「独立 Topic 为
 *       {@code tc/v1/{projectKey}/{deviceKey}/up/ota/progress}」，与
 *       {@code OtaProgressUplinkHandler} 的 {@code ota/progress} 一致。</li>
 *   <li>{@code up/ota/health}：ADR0132「健康 Topic 为
 *       {@code tc/v1/{projectKey}/{deviceKey}/up/ota/health}」。</li>
 *   <li>{@code down/ota/available}：平台 {@code OtaNotificationCodec.topic} 生成
 *       {@code tc/v1/<projectKey>/<deviceKey>/down/ota/available}。</li>
 *   <li>{@code down/ota/download/response}：平台 {@code OtaDownloadResponseCodec.topic} 由通知
 *       Topic 的 {@code available} 段替换为 {@code download/response} 得到，
 *       即 {@code .../down/ota/download/response}。</li>
 *   <li>{@code up|down/ota/install-stop/operation}、{@code operation/report}、
 *       {@code status/query}、{@code status/report}：ADR0137「MQTT 使用
 *       ota/install-stop/operation、operation/report、status/query、status/report 四个后缀」，
 *       方向由 {@code OtaInstallStopDeliveryService}（下行 operation/status query）与
 *       {@code OtaInstallStopUplinkHandler}（上行 operation/status report）确定。</li>
 *   <li>{@code down/ota/commit/permit}：平台 {@code OtaCommitPermitDeliveryService.prepare} 用
 *       {@code tc/v1/<projectKey>/<deviceKey>/down/ota/commit/permit} 拼出唯一许可下行 Topic，
 *       {@code EmqxOtaCommitPermitPublisher.topic} 也返回同一字符串；它是 ADR0132 独立提交许可
 *       合同 {@code tc-ota-commit-permit/v1} 的投递通道。</li>
 *   <li>{@code up/ota/commit/receipt}：平台 {@code OtaCommitReceiptUplinkHandler} 只分流
 *       {@code ota/commit/receipt} 消息类型，方向为设备上行；它是平台把设备真实提交事实采成
 *       {@code SUCCEEDED} 的唯一入口，模拟器因此必须与进度、健康走同一条已认证连接。</li>
 * </ul>
 * </p>
 *
 * <p><b>为什么标识符也要校验：</b>Topic 用字符串拼接，若 projectKey/deviceKey 含 {@code /}、
 * {@code +} 或 {@code #}，设备就能把报文发到别的设备甚至订阅通配层。这里复用平台
 * {@code MqttUplinkTopic}/{@code OtaNotificationCodec} 冻结的同一层字符集。</p>
 */
public final class OtaDeviceTopics {

    /** 项目短标识占位符。 */
    public static final String PROJECT_PLACEHOLDER = "{projectKey}";

    /** 设备短标识占位符。 */
    public static final String DEVICE_PLACEHOLDER = "{deviceKey}";

    /** 设备→平台：下载申请（ADR0128）。 */
    public static final String DOWNLOAD_REQUEST = "tc/v1/{projectKey}/{deviceKey}/up/ota/download/request";

    /** 设备→平台：认证执行进度，平台闭集只接受 VERIFYING/INSTALLING/REBOOTING/HEALTH_CHECKING（ADR0131）。 */
    public static final String PROGRESS = "tc/v1/{projectKey}/{deviceKey}/up/ota/progress";

    /** 设备→平台：健康确认（ADR0132）。 */
    public static final String HEALTH = "tc/v1/{projectKey}/{deviceKey}/up/ota/health";

    /** 设备→平台：真实提交回执（ADR0132），平台据此把作业采用为 {@code SUCCEEDED}。 */
    public static final String COMMIT_RECEIPT = "tc/v1/{projectKey}/{deviceKey}/up/ota/commit/receipt";

    /** 设备→平台：安装前停止操作报告（ADR0137）。 */
    public static final String INSTALL_STOP_OPERATION_REPORT =
            "tc/v1/{projectKey}/{deviceKey}/up/ota/install-stop/operation/report";

    /** 设备→平台：安装前停止状态查询报告（ADR0137）。 */
    public static final String INSTALL_STOP_STATUS_REPORT =
            "tc/v1/{projectKey}/{deviceKey}/up/ota/install-stop/status/report";

    /** 平台→设备：可升级通知，不含下载地址（ADR0128/0129）。 */
    public static final String AVAILABLE = "tc/v1/{projectKey}/{deviceKey}/down/ota/available";

    /** 平台→设备：下载响应（授权 + 清单 + 签名 + 临时地址）。 */
    public static final String DOWNLOAD_RESPONSE = "tc/v1/{projectKey}/{deviceKey}/down/ota/download/response";

    /** 平台→设备：唯一提交许可（ADR0132），设备只有匹配它才允许声明真实提交。 */
    public static final String COMMIT_PERMIT = "tc/v1/{projectKey}/{deviceKey}/down/ota/commit/permit";

    /** 平台→设备：安装前停止操作命令（ADR0137）。 */
    public static final String INSTALL_STOP_OPERATION = "tc/v1/{projectKey}/{deviceKey}/down/ota/install-stop/operation";

    /** 平台→设备：安装前停止状态只读查询（ADR0137）。 */
    public static final String INSTALL_STOP_STATUS_QUERY =
            "tc/v1/{projectKey}/{deviceKey}/down/ota/install-stop/status/query";

    /** 已冻结的模板闭集；替换助手只接受这些模板，避免调用方拼出未审计 Topic。 */
    private static final Set<String> TEMPLATES = Set.of(
            DOWNLOAD_REQUEST, PROGRESS, HEALTH, COMMIT_RECEIPT, INSTALL_STOP_OPERATION_REPORT,
            INSTALL_STOP_STATUS_REPORT, AVAILABLE, DOWNLOAD_RESPONSE, COMMIT_PERMIT,
            INSTALL_STOP_OPERATION, INSTALL_STOP_STATUS_QUERY);

    /** Topic 每一层的安全字符集，与平台 MqttUplinkTopic 冻结规则一致。 */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    /** 工具类不允许实例化。 */
    private OtaDeviceTopics() {
    }

    /**
     * 用项目/设备短标识替换模板并返回设备专属 Topic。
     *
     * @param template 必须是本类声明的模板常量之一
     * @param projectKey 项目短标识，必须匹配 {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}}
     * @param deviceKey 设备短标识，规则同上
     * @return 无占位符的完整 Topic
     * @throws IllegalArgumentException 模板未登记或标识符非法时
     */
    public static String forDevice(String template, String projectKey, String deviceKey) {
        if (template == null || !TEMPLATES.contains(template)) {
            throw new IllegalArgumentException("未登记的 OTA Topic 模板");
        }
        if (!segment(projectKey) || !segment(deviceKey)) {
            throw new IllegalArgumentException("OTA Topic 标识符不合法");
        }
        return template.replace(PROJECT_PLACEHOLDER, projectKey).replace(DEVICE_PLACEHOLDER, deviceKey);
    }

    /**
     * 判断一个下行 Topic 是否精确匹配给定模板与设备身份。
     *
     * <p>Broker ACL 之外的第二道防线：适配器不能因为订阅了通配层就把别的项目/设备的报文交给
     * 本设备状态机。</p>
     *
     * @param template 必须是本类声明的模板常量之一
     * @param projectKey 期望项目短标识
     * @param deviceKey 期望设备短标识
     * @param topic 实际收到的 Topic
     * @return {@code true} 表示完全一致
     */
    public static boolean matches(String template, String projectKey, String deviceKey, String topic) {
        try {
            return forDevice(template, projectKey, deviceKey).equals(topic);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 复用平台冻结的标识符语法。 */
    private static boolean segment(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }
}
