package com.things.link.ingestion.application.access;

import com.things.link.device.application.DeviceAccessAcceptancePort;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.StandardUplinkMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 新协议上行的受理用例：先确权解析与幂等判定，再交给总线，总线确认后落受理事实。
 *
 * <p>顺序不可交换，每一步都有明确理由：解析与时间校验在前，永久非法载荷不会占用总线；幂等判定在交接前，
 * 重放不会再次上车、同键异载荷直接拒绝；<b>交接成功才算受理</b>，事实只是受理证据而不是先占的锁——先写事实
 * 再发布会在发布失败时留下「已受理但消息从未上车」的假事实（接入合同 §4.3）。</p>
 *
 * <p>物模型校验、属性范围、规则执行都在受理之后的标准管道内完成，因此成功返回值只代表「平台已可靠接管」，
 * 不代表设备数据已经生效（接入合同 §3.1）。</p>
 *
 * <p>业务预算不在这里扣减：它扣在设备面唯一已认证入口（认证用例）上，覆盖上报、领取与回复，避免某个端点
 * 漏扣而让轮询或协议切换成为绕过限流的通道。本用例只做载荷契约、幂等与交接三件事。</p>
 */
public class DeviceAccessUplinkIngressService {

    /** 协议无关标准化器。 */
    private final DeviceAccessUplinkNormalizer normalizer;

    /** 总线可靠接管端口。 */
    private final DeviceAccessUplinkPublisher publisher;

    /** 与 device 域共用的幂等受理事实端口。 */
    private final DeviceAccessAcceptancePort acceptance;

    /**
     * 创建新协议上行受理用例。
     *
     * @param normalizer 协议无关标准化器
     * @param publisher 总线可靠接管端口
     * @param acceptance 幂等受理事实端口
     */
    public DeviceAccessUplinkIngressService(DeviceAccessUplinkNormalizer normalizer,
                                            DeviceAccessUplinkPublisher publisher,
                                            DeviceAccessAcceptancePort acceptance) {
        this.normalizer = normalizer;
        this.publisher = publisher;
        this.acceptance = acceptance;
    }

    /**
     * 受理一条新协议上行。
     *
     * @param uplink 接入面冻结的新协议信封
     * @return 受理结果：首次受理或重放，并携带供协议层构造响应的消息标识与接收时刻
     * @throws InvalidUplinkMessageException 载荷违反冻结契约或时间超限
     * @throws DeviceAccessIdempotencyConflictException 同键异载荷：必须拒绝且保持首次事实不变
     * @throws DeviceAccessHandoffUnavailableException 总线未确认接管：协议层必须返回 HANDOFF_UNAVAILABLE
     */
    public DeviceAccessUplinkAcceptance accept(DeviceAccessUplinkMessage uplink) {
        StandardUplinkMessage normalized = normalizer.normalize(uplink);
        String payloadDigest = DeviceAccessPayloadDigest.sha256Hex(uplink.businessPayload());
        DeviceAccessAcceptancePort.Attempt attempt = new DeviceAccessAcceptancePort.Attempt(
                normalized.tenantId(), normalized.projectId(), normalized.deviceId(), normalized.messageId(),
                normalized.protocol(), payloadDigest, normalized.receivedAt());

        DeviceAccessAcceptancePort.Decision decision = acceptance.decide(attempt);
        if (decision instanceof DeviceAccessAcceptancePort.Decision.Conflict) {
            // 同一 messageId 换了载荷：不交接、不覆盖首次事实，协议层返回 409／4.09。
            throw new DeviceAccessIdempotencyConflictException(normalized.messageId());
        }
        if (decision instanceof DeviceAccessAcceptancePort.Decision.Duplicate duplicate) {
            // 重放只回首次结果，绝不再次上车；重复投递的下游防护不因此被削弱。
            return DeviceAccessUplinkAcceptance.duplicate(duplicate.acceptance());
        }

        // 判定只可能是 Fresh（sealed 接口的第三个也是最后一个分支）：继续交接，事实随后写入。
        publisher.publish(normalized);
        DeviceAccessAcceptancePort.Recording recording = acceptance.record(attempt);
        if (!recording.acceptance().payloadDigest().equals(payloadDigest)) {
            // 并发同键异载荷：本次已上车但事实以先写入者为准，仍必须把冲突告知设备。
            throw new DeviceAccessIdempotencyConflictException(normalized.messageId());
        }
        return recording.firstRecording()
                ? DeviceAccessUplinkAcceptance.accepted(recording.acceptance())
                : DeviceAccessUplinkAcceptance.duplicate(recording.acceptance());
    }

    /** 受理结果；重放与首次受理返回同一响应形状，只是携带首次受理事实。 */
    public record DeviceAccessUplinkAcceptance(Status status, UUID messageId, Instant receivedAt,
                                               Instant acceptedAt) {

        /** 受理状态。 */
        public enum Status {
            /** 本次尝试首次被受理，标准上行已由总线接管。 */
            ACCEPTED,
            /** 同键同摘要的重放，返回首次结果且未再次交接。 */
            DUPLICATE
        }

        /**
         * 由受理事实构造首次受理结果。
         *
         * @param acceptance 已写入的受理事实
         * @return 首次受理结果
         */
        static DeviceAccessUplinkAcceptance accepted(DeviceAccessAcceptancePort.Acceptance acceptance) {
            return new DeviceAccessUplinkAcceptance(Status.ACCEPTED, acceptance.messageId(),
                    acceptance.receivedAt(), acceptance.acceptedAt());
        }

        /**
         * 由首次受理事实构造重放结果。
         *
         * @param acceptance 首次受理事实
         * @return 重放结果
         */
        static DeviceAccessUplinkAcceptance duplicate(DeviceAccessAcceptancePort.Acceptance acceptance) {
            return new DeviceAccessUplinkAcceptance(Status.DUPLICATE, acceptance.messageId(),
                    acceptance.receivedAt(), acceptance.acceptedAt());
        }
    }
}
