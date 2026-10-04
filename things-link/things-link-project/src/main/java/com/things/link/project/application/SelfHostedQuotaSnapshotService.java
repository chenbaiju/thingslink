package com.things.link.project.application;

import com.things.link.entitlement.application.GrantV1Verification;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 通用自部署本地实验额度快照。调用者提供已确权安装身份和已装配信任根；
 * 快照用于展示与验收，业务写入由数据库事实触发器负责原子拒绝。
 */
public final class SelfHostedQuotaSnapshotService {
    private final SelfHostedGrantImportService importer;
    private final SelfHostedDeviceUsageReader devices;
    private final SelfHostedMessageUsageReader messages;
    private final Clock clock;

    public SelfHostedQuotaSnapshotService(SelfHostedGrantImportService importer,
                                          SelfHostedDeviceUsageReader devices,
                                          SelfHostedMessageUsageReader messages) {
        this(importer, devices, messages, Clock.systemUTC());
    }

    public SelfHostedQuotaSnapshotService(SelfHostedGrantImportService importer,
                                          SelfHostedDeviceUsageReader devices,
                                          SelfHostedMessageUsageReader messages, Clock clock) {
        this.importer = Objects.requireNonNull(importer, "importer");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Snapshot read(SelfHostedGrantImportService.Installation identity) {
        GrantV1Verification.VerifiedGrant grant = importer.readCurrent(identity)
                .orElseThrow(() -> new IllegalStateException("自部署授权尚未导入"));
        var now = clock.instant();
        if (now.isBefore(grant.startsAt()) || now.isBefore(grant.issuedAt())
                || grant.endsAt() != null && !now.isBefore(grant.endsAt())) {
            throw new IllegalStateException("自部署本地实验授权不在有效首期内");
        }
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return new Snapshot(grant.tier(), day,
                metric(grant.quotas().get("DEVICES_MAX"), devices.activeDevices(identity.tenantId())),
                metric(grant.quotas().get("UPLINK_MESSAGE_DAILY"),
                        messages.acceptedMessages(identity.tenantId(), day, SelfHostedMessageUsageReader.Direction.UP)),
                metric(grant.quotas().get("DOWNLINK_MESSAGE_DAILY"),
                        messages.acceptedMessages(identity.tenantId(), day, SelfHostedMessageUsageReader.Direction.DOWN)));
    }

    private static Metric metric(Long limit, long used) {
        if (limit == null || limit < 0 || used < 0) {
            throw new IllegalStateException("自部署额度或权威用量无效");
        }
        return new Metric(limit, used, Math.max(0, limit - used), used >= limit);
    }

    public record Metric(long limit, long used, long remaining, boolean atLimit) { }

    public record Snapshot(String tier, LocalDate utcDay, Metric devices,
                           Metric uplinkMessages, Metric downlinkMessages) { }
}
