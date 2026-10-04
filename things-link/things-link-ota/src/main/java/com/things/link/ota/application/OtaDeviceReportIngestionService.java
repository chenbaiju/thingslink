package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaDeviceReportState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 认证设备报告的单一事务边界，不创建管理账号或替设备取得升级授权。 */
@Service
public class OtaDeviceReportIngestionService {
    /** 原始Broker时间的新鲜度，不以Kafka消费时间重置。 */
    private static final Duration FRESHNESS = Duration.ofMinutes(5);
    /** 允许有限的Broker时钟超前，超出则拒绝。 */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    /** 当前设备凭据与绑定共享锁。 */ private final OtaDeviceIdentityPort devices;
    /** 当前报告及不可降低安全下限。 */ private final OtaDeviceReportRepository reports;
    /** 项目ACTIVE共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 无账号设备后台的事务局部隔离范围。 */ private final TransactionLocalRlsScope rls;
    /** 独立于旧报告头的真实提交下限。 */ private final OtaKnownSecurityFloor floors;
    /** 严格闭集报告及规范摘要。 */ private final OtaDeviceReportCodec codec = new OtaDeviceReportCodec();

    /** 只依赖明确的application端口与本域持久边界。 */
    public OtaDeviceReportIngestionService(OtaDeviceIdentityPort devices, OtaDeviceReportRepository reports,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, OtaKnownSecurityFloor floors) {
        this.devices = devices;
        this.reports = reports;
        this.lifecycle = lifecycle;
        this.rls = rls; this.floors = floors;
    }

    /** 已认证raw来源才可调用，当前设备身份和报告提交位于同一非只读事务。 */
    @DataPlaneDatabase
    @Transactional(timeout = 5)
    public Outcome accept(AuthenticatedDeviceIdentity authenticated, byte[] payload, Instant brokerReceivedAt) {
        if (authenticated == null || brokerReceivedAt == null) throw invalid();
        var decoded = codec.decode(payload);
        rls.establish(authenticated.tenantId(), authenticated.projectId());
        if (!lifecycle.lockActiveForWrite(authenticated.tenantId(), authenticated.projectId())) throw invalid();
        var device = devices.lockCurrent(authenticated).orElseThrow(OtaDeviceReportIngestionService::invalid);
        if (!matchesModel(device, decoded.value()) || !floors.accepts(authenticated.tenantId(), authenticated.projectId(),
                authenticated.deviceId(), decoded.value())) throw invalid();
        reports.lockRegistration(authenticated.tenantId(), authenticated.projectId(), authenticated.deviceId());
        var previous = reports.find(authenticated.projectId(), authenticated.deviceId(), true, false).orElse(null);
        if (previous != null) {
            if (previous.credentialVersion() > authenticated.credentialVersion()
                    || previous.committedSecurityVersion() > decoded.value().committedSecurityVersion()) throw invalid();
            if (previous.credentialVersion() == authenticated.credentialVersion()) {
                if (previous.reportSequence() > decoded.value().reportSequence()) throw invalid();
                if (previous.reportSequence() == decoded.value().reportSequence()) {
                    if (!previous.reportHash().equals(decoded.sha256())
                            || !Arrays.equals(previous.canonical(), decoded.canonical())) throw invalid();
                    if (!devices.credentialValid(authenticated)) throw invalid();
                    return Outcome.REPLAY;
                }
            }
            if (previous.revision() == Long.MAX_VALUE) throw invalid();
        }
        Instant now = reports.currentTime();
        if (!fresh(brokerReceivedAt, now)) throw invalid();
        if (previous != null && now.isBefore(previous.acceptedAt())) now = previous.acceptedAt();
        var next = new OtaDeviceReportState(authenticated.tenantId(), authenticated.projectId(),
                authenticated.deviceId(), authenticated.credentialVersion(), decoded.value().reportSequence(),
                previous == null ? 1L : previous.revision() + 1, decoded.value().committedSecurityVersion(),
                decoded.canonical(), decoded.sha256(), brokerReceivedAt.truncatedTo(ChronoUnit.MICROS), now);
        if (!devices.credentialValid(authenticated)) throw invalid();
        if (previous == null) reports.create(next);
        else if (!reports.replace(previous.revision(), next)) throw invalid();
        return Outcome.ACCEPTED;
    }

    /** 运行模型只能对应当前权威绑定，不能由设备正文切换模型。 */
    static boolean matchesModel(OtaDeviceIdentity device, OtaDeviceReportCodec.Report report) {
        return Objects.equals(device.thingModelVersionId(), report.thingModelVersionId())
                && Objects.equals(device.schemaDigestAlgorithm(), report.thingModelSchemaDigestAlgorithm())
                && Objects.equals(device.schemaDigest(), report.thingModelSchemaDigest())
                && Objects.equals(device.schemaProfile(), report.propertyProfile());
    }

    /** 队列积压不会重置Broker接收时间，边界包含恰好五分钟。 */
    static boolean fresh(Instant receivedAt, Instant now) {
        return receivedAt != null && !receivedAt.isBefore(now.minus(FRESHNESS))
                && !receivedAt.isAfter(now.plus(FUTURE_SKEW));
    }

    /** 固定永久拒绝，不输出设备报告正文。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OTA报告身份、顺序、模型或新鲜度不满足接纳合同");
    }

    /** 重放不会刷新任何持久时间或安全下限。 */
    public enum Outcome { ACCEPTED, REPLAY }
}
