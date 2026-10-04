package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ResourcePackageAdjustment;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantResourcePackage;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.plan.ResourcePackageDimension;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 有限期人工运营调整（S14-4b）：给某个租户的某个维度在指定窗口内追加额度。
 *
 * <h2>为什么不是第二套机制</h2>
 * 调整事实写在 {@code sys_tenant_resource_package} 上（{@code source = 'OPERATION_ADJUSTMENT'}，
 * 4a 已预留该取值并冻结「由 4b 记录操作人与原因」）。因此：
 * <ul>
 *   <li><b>合成只有一份</b>：SQL 侧 {@code tenant_resource_package_addon()} 与 Java 侧
 *       {@code findActiveAdditions()} 都只按「ACTIVE + 处于自身窗口 + 维度/单位/窗口匹配」求和，
 *       不看来源；三处真实准入（项目配额投影、设备数据面、UTC 日额度决策）与租户有效权益投影
 *       自动包含人工调整；</li>
 *   <li><b>状态机只有一份</b>：到期与 PENDING→ACTIVE 推进仍由
 *       {@link TenantResourcePackageService#advance(Instant)} 承担；</li>
 *   <li><b>缓存失效协议只有一份</b>：复用 S7 的 {@link QuotaPolicyAssignmentService} CAS 绑定，
 *       与购买包走同一个版本轴，不新增第二套版本方案。</li>
 * </ul>
 *
 * <h2>冻结口径</h2>
 * <ul>
 *   <li>必须记录原因、操作人与幂等键；同一租户内幂等键唯一，重放返回既有调整（且必须与本次
 *       提交逐项相符，否则 {@link ProjectErrorCode#ADJUSTMENT_KEY_CONFLICT}）；</li>
 *   <li>起点必须早于终点、终点必须在创建时仍未来、整段时长不超过
 *       {@value #MAX_ADJUSTMENT_MONTHS} 个 UTC 日历月。允许倒填起点（补偿已发生的事件），
 *       但整段窗口仍受上限约束，因此不存在「一百年有效」的变相永久授权；</li>
 *   <li>订阅处于 ACTIVE/GRACE 才立刻生效，否则落 {@code PENDING}（P4：订阅受限时不让额度
 *       先于订阅生效），订阅恢复后由时间推进激活；</li>
 *   <li>撤销只接受人工调整行（购买包退款属 S14-5），且只对 ACTIVE/PENDING 生效：已到期是时间
 *       事实，改写状态只会篡改历史而不改变任何有效值。</li>
 * </ul>
 *
 * <h2>共享模板绝不被触碰</h2>
 * 本服务只写 {@code sys_tenant_resource_package} 与审计，从不 UPDATE {@code sys_quota_policy}：
 * 人工调整是**租户级**事实，改共享模板会同时改变所有绑定该模板的租户，且会绕过目录的不可变
 * 修订版语义。真库用例以「模板行逐列 + 版本号在调整前后完全相同」钉住这一点。
 *
 * <h2>授权</h2>
 * 本服务保留显式租户与操作人，供受信应用用例复用；HTTP入口由
 * {@link CommercialOperationsService} 按ADR0164校验独立平台授权并填入认证主体。
 * 不得用项目级权限或客户端operatorId冒充平台运营权限。
 */
@Service
public class TenantEntitlementAdjustmentService {

    /**
     * 单次人工调整的时长上限（UTC 日历月）。
     *
     * <p>运营口径而非产品参数：调整是临时补偿工具，不是绕过产品目录的长期授权通道。需要长期
     * 权益应当走产品修订版或购买；超过上限的请求显式拒绝，而不是悄悄截断。
     */
    public static final int MAX_ADJUSTMENT_MONTHS = 24;

    /** 审计详情里保留的原因长度上限，与 {@code adjustment_reason} 列一致。 */
    private static final int MAX_REASON_LENGTH = ResourcePackageAdjustment.MAX_REASON_LENGTH;

    /** 租户存在性校验端口（与订单/资源包下单共用同一权威检查，不新增第二份判定）。 */
    private final TenantOrderRepository orderRepository;
    /** 资源包（含人工调整）事实读写端口。 */
    private final TenantResourcePackageRepository packageRepository;
    /** 订阅状态读取与租户行锁（判定 ACTIVE/PENDING 并复用同一缓存版本轴）。 */
    private final TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository;
    /** S7 冻结的运行时策略 CAS 绑定用例；调整变化必须复用它失效缓存。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 商业审计写入（授予 / 撤销）。 */
    private final AuditLogService auditLogService;
    /** 时间来源；生产固定 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    /**
     * 生产构造器：时间取系统 UTC 时钟。
     *
     * @param orderRepository 租户存在性校验端口
     * @param packageRepository 资源包与人工调整事实端口
     * @param subscriptionLifecycleRepository 订阅状态与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     */
    @Autowired
    public TenantEntitlementAdjustmentService(TenantOrderRepository orderRepository,
                                              TenantResourcePackageRepository packageRepository,
                                              TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                                              QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                              AuditLogService auditLogService) {
        this(orderRepository, packageRepository, subscriptionLifecycleRepository,
                quotaPolicyAssignmentService, auditLogService, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造器：真库用例用它把「创建时刻 / 窗口」冻结成固定 Instant。
     *
     * @param orderRepository 租户存在性校验端口
     * @param packageRepository 资源包与人工调整事实端口
     * @param subscriptionLifecycleRepository 订阅状态与租户行锁
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     * @param auditLogService 商业审计写入
     * @param clock 时间来源
     */
    public TenantEntitlementAdjustmentService(TenantOrderRepository orderRepository,
                                              TenantResourcePackageRepository packageRepository,
                                              TenantSubscriptionLifecycleRepository subscriptionLifecycleRepository,
                                              QuotaPolicyAssignmentService quotaPolicyAssignmentService,
                                              AuditLogService auditLogService,
                                              Clock clock) {
        this.orderRepository = orderRepository;
        this.packageRepository = packageRepository;
        this.subscriptionLifecycleRepository = subscriptionLifecycleRepository;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 授予一份有人、有原因、有期限、可重放的运营调整。
     *
     * @param tenantId 归属租户
     * @param request 提交请求（维度、额度、窗口、原因、操作人、幂等键）
     * @return 写入结果；同一幂等键且逐项相符的重放返回 {@code created = false}
     * @throws BusinessException 租户不存在（404）、维度不可扩容（50034）、额度非正（400）、
     *         元数据缺失（50040）、有效期不合法（50039）、幂等键冲突（50041）
     */
    @Transactional
    public EntitlementAdjustmentResult createAdjustment(UUID tenantId, EntitlementAdjustmentRequest request) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(request, "调整请求不得为空");
        if (!orderRepository.tenantExists(tenantId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        ResourcePackageDimension dimension = requireAdjustableDimension(request.dimensionCode());
        if (request.amount() <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "调整额度必须为正整数");
        }
        ResourcePackageAdjustment metadata = requireMetadata(request);
        // 微秒归一化：PostgreSQL timestamptz 的精度是微秒，先把请求对齐到可精确落库的值，
        // 幂等重放才能逐项比较而不被亚微秒舍入误判成「另一份提交」。
        Instant startsAt = request.startsAt() == null ? null : normalize(request.startsAt());
        Instant endsAt = request.endsAt() == null ? null : normalize(request.endsAt());
        Instant now = clock.instant();
        requireValidPeriod(startsAt, endsAt, now);

        Optional<TenantResourcePackage> existing =
                packageRepository.findAdjustmentByKey(tenantId, metadata.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), dimension, request.amount(), startsAt, endsAt, metadata);
        }

        // 租户行锁：与订阅生效/包生效共用同一把锁与同一个策略绑定版本轴。
        long assignmentVersion = subscriptionLifecycleRepository
                .lockTenantAndReadAssignmentVersion(tenantId);
        boolean subscriptionEntitled = currentlyEntitled(tenantId);
        ResourcePackageStatus status = subscriptionEntitled
                ? ResourcePackageStatus.ACTIVE : ResourcePackageStatus.PENDING;

        UUID adjustmentId;
        try {
            adjustmentId = packageRepository.insert(tenantId, dimension.name(), request.amount(),
                    dimension.fixedUnit(), dimension.window(), startsAt, endsAt,
                    ResourcePackageSource.OPERATION_ADJUSTMENT, null, metadata, status);
        } catch (DuplicateKeyException conflict) {
            // 并发的同键提交：数据库是最终仲裁者。本事务已失败无法回读，明确返回冲突让调用方重试，
            // 重试会走上面的幂等重放分支并收敛为「返回既有事实」。
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_KEY_CONFLICT,
                    "幂等键已被并发提交占用，请重试读取既有调整");
        }

        auditLogService.record(new AuditLogEntry(tenantId, null, metadata.operatorId(),
                "tenant_resource_package", adjustmentId, "commercial.adjustment.granted",
                grantDetails(adjustmentId, dimension, request.amount(), startsAt, endsAt, status,
                        subscriptionEntitled, metadata)));

        // 只有「此刻已在窗口内」的 ACTIVE 调整才改变当前有效值；未来起点的调整不推进版本，
        // 避免无谓失效（到点自然进入合成窗口，不需要额外推进）。
        if (status == ResourcePackageStatus.ACTIVE && !startsAt.isAfter(now)) {
            quotaPolicyAssignmentService.assign(tenantId,
                    subscriptionLifecycleRepository.readPolicyId(tenantId), assignmentVersion);
        }
        return new EntitlementAdjustmentResult(adjustmentId, status, startsAt, endsAt, true);
    }

    /**
     * 撤销一份人工调整：立刻停止它对有效权益的贡献，并留下审计。
     *
     * <p>确权后先锁租户再重新读取包状态，与购买退款及生命周期推进共用锁序；等待期间已到期的
     * 调整仍返回既有不可撤销错误，不以扫描时的 ACTIVE 状态覆盖终态。
     *
     * @param tenantId 归属租户
     * @param adjustmentId 调整行 ID
     * @param operatorId 操作人账号 ID
     * @param reason 撤销原因
     * @return 本次确实把状态推进为 {@code CANCELLED} 时为 {@code true}；已是取消态时为 {@code false}
     * @throws BusinessException 调整不存在或不属于该租户（404）、不是人工调整（50036）、
     *         已到期不可撤销（50038）、缺少原因或操作人（50040）
     */
    @Transactional
    public boolean revokeAdjustment(UUID tenantId, UUID adjustmentId, UUID operatorId, String reason) {
        Objects.requireNonNull(tenantId, "租户 ID 不得为空");
        Objects.requireNonNull(adjustmentId, "调整 ID 不得为空");
        if (operatorId == null) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED,
                    "撤销运营调整必须记录操作人");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED,
                    "撤销运营调整必须记录原因");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "撤销原因过长");
        }

        // 跨租户传别人的调整 ID 与「不存在」返回同一个码：不把 ID 存在性变成跨租户探测信号。
        TenantResourcePackage adjustment = packageRepository.findById(adjustmentId)
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ADJUSTMENT_NOT_FOUND));
        if (adjustment.source() != ResourcePackageSource.OPERATION_ADJUSTMENT) {
            throw new BusinessException(ProjectErrorCode.PACKAGE_NOT_ADJUSTMENT);
        }
        long assignmentVersion = subscriptionLifecycleRepository.lockTenantAndReadAssignmentVersion(tenantId);
        adjustment = packageRepository.findById(adjustmentId)
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.ADJUSTMENT_NOT_FOUND));
        if (adjustment.status() == ResourcePackageStatus.CANCELLED) {
            return false;
        }
        if (adjustment.status() != ResourcePackageStatus.ACTIVE
                && adjustment.status() != ResourcePackageStatus.PENDING) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_NOT_REVOCABLE);
        }

        Instant now = clock.instant();
        // 必须在置 CANCELLED **之前**判断它此刻是否在贡献额度，且判定要走数据库时间
        // （与合成函数同一基准）：用 JVM 时钟比较数据库写入的窗口时，两者相差几毫秒就会把
        // 在窗口内的调整判成「还没起算」，于是调整被撤销却跳过了缓存失效（D-180 的同类缺口）。
        boolean wasEffective = packageRepository.isCurrentlyEffective(adjustmentId);
        if (!packageRepository.cancelAdjustment(adjustmentId, now)) {
            // 并发撤销：另一个请求已经推进为 CANCELLED，本次不再写审计、不再失效缓存。
            return false;
        }
        auditLogService.record(new AuditLogEntry(tenantId, null, operatorId,
                "tenant_resource_package", adjustmentId, "commercial.adjustment.revoked",
                revokeDetails(adjustment, reason, now)));

        // 只有撤销前「确实在窗口内贡献额度」的调整才需要失效缓存（判据由数据库时间给出）。
        if (wasEffective) {
            quotaPolicyAssignmentService.assign(tenantId,
                    subscriptionLifecycleRepository.readPolicyId(tenantId), assignmentVersion);
        }
        return true;
    }

    /**
     * 校验并归一化提交请求里的维度。
     *
     * @param dimensionCode 目标维度编码
     * @return 登记的可扩容维度
     * @throws BusinessException 编码未知或该维度单位随档位变化（历史窗口）
     */
    private ResourcePackageDimension requireAdjustableDimension(String dimensionCode) {
        if (dimensionCode == null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "调整维度编码不得为空");
        }
        ResourcePackageDimension dimension = ResourcePackageDimension.fromCode(dimensionCode)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED));
        if (dimension.fixedUnit() == null) {
            // 历史窗口的单位随基础档冻结值取 DAY 或 MONTH，无租户上下文时无法安全解析。
            throw new BusinessException(ProjectErrorCode.PACKAGE_DIMENSION_NOT_SUPPORTED,
                    "历史窗口维度暂不支持人工调整：其单位随档位冻结值变化");
        }
        return dimension;
    }

    /**
     * 校验原因、操作人与幂等键并构造领域元数据。
     *
     * @param request 提交请求
     * @return 校验通过的调整元数据
     * @throws BusinessException 任一项缺失或形状不合法（50040）
     */
    private ResourcePackageAdjustment requireMetadata(EntitlementAdjustmentRequest request) {
        if (request.reason() == null || request.reason().isBlank()
                || request.reason().length() > MAX_REASON_LENGTH
                || request.operatorId() == null
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED);
        }
        try {
            return new ResourcePackageAdjustment(request.reason().trim(), request.operatorId(),
                    request.idempotencyKey());
        } catch (IllegalArgumentException invalid) {
            // 幂等键形状等结构约束在域类型上只有一份实现，这里把它的拒绝映射成同一个可读错误码。
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_METADATA_REQUIRED, invalid.getMessage());
        }
    }

    /**
     * 校验调整窗口：起点早于终点、终点仍在未来、整段不超过 {@value #MAX_ADJUSTMENT_MONTHS} 个月。
     *
     * @param startsAt 归一化后的起点；允许为空以外的值
     * @param endsAt 归一化后的终点
     * @param now 创建时刻（UTC）
     * @throws BusinessException 窗口缺失或不合法（50039）
     */
    private void requireValidPeriod(Instant startsAt, Instant endsAt, Instant now) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt) || !endsAt.isAfter(now)) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID);
        }
        Instant maxEnd;
        try {
            maxEnd = startsAt.atZone(ZoneOffset.UTC).plusMonths(MAX_ADJUSTMENT_MONTHS).toInstant();
        } catch (java.time.DateTimeException invalid) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID);
        }
        if (endsAt.isAfter(maxEnd)) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_PERIOD_INVALID,
                    "单次人工调整不得超过 " + MAX_ADJUSTMENT_MONTHS + " 个 UTC 日历月");
        }
    }

    /**
     * 处理幂等重放：既有事实必须与本次提交逐项相符，否则显式冲突。
     *
     * @param existing 幂等键命中的既有调整
     * @param dimension 本次提交的目标维度
     * @param amount 本次提交的额度
     * @param startsAt 本次提交的起点
     * @param endsAt 本次提交的终点
     * @param metadata 本次提交的元数据
     * @return 既有事实的写入结果，{@code created = false}
     * @throws BusinessException 同一幂等键对应另一份不同提交（50041）
     */
    private EntitlementAdjustmentResult replay(TenantResourcePackage existing,
                                               ResourcePackageDimension dimension,
                                               long amount, Instant startsAt, Instant endsAt,
                                               ResourcePackageAdjustment metadata) {
        boolean identical = existing.source() == ResourcePackageSource.OPERATION_ADJUSTMENT
                && existing.dimensionCode().equals(dimension.name())
                && existing.amount() == amount
                && existing.unit().equals(dimension.fixedUnit())
                && existing.window().equals(dimension.window())
                && existing.startsAt().equals(startsAt)
                && existing.endsAt().equals(endsAt)
                && existing.adjustment() != null
                && existing.adjustment().reason().equals(metadata.reason())
                && existing.adjustment().operatorId().equals(metadata.operatorId());
        if (!identical) {
            throw new BusinessException(ProjectErrorCode.ADJUSTMENT_KEY_CONFLICT);
        }
        return new EntitlementAdjustmentResult(existing.id(), existing.status(),
                existing.startsAt(), existing.endsAt(), false);
    }

    /**
     * 判断租户当前订阅是否允许调整立刻生效（P4/P6：ACTIVE 或 GRACE）。
     *
     * @param tenantId 租户 ID
     * @return 当前订阅状态为 ACTIVE 或 GRACE 时返回 {@code true}
     */
    private boolean currentlyEntitled(UUID tenantId) {
        return subscriptionLifecycleRepository.findCurrentState(tenantId)
                .map(SubscriptionLifecycleState::status)
                .filter(status -> status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.GRACE)
                .isPresent();
    }

    /**
     * 把 Instant 对齐到微秒，保证落库值与请求值逐位相等（幂等重放比较的前提）。
     *
     * @param value 原始时刻
     * @return 截断到微秒的时刻
     */
    private Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 授予事件的审计详情。
     *
     * @param adjustmentId 调整行 ID
     * @param dimension 目标维度
     * @param amount 额度
     * @param startsAt 起点
     * @param endsAt 终点
     * @param status 落库状态
     * @param subscriptionEntitled 订阅是否处于 ACTIVE/GRACE
     * @param metadata 调整元数据
     * @return 结构化详情
     */
    private Map<String, Object> grantDetails(UUID adjustmentId, ResourcePackageDimension dimension,
                                             long amount, Instant startsAt, Instant endsAt,
                                             ResourcePackageStatus status, boolean subscriptionEntitled,
                                             ResourcePackageAdjustment metadata) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("adjustmentId", adjustmentId.toString());
        details.put("dimensionCode", dimension.name());
        details.put("amount", amount);
        details.put("unit", dimension.fixedUnit());
        details.put("window", dimension.window());
        details.put("startsAt", startsAt.toString());
        details.put("endsAt", endsAt.toString());
        details.put("status", status.name());
        details.put("source", ResourcePackageSource.OPERATION_ADJUSTMENT.name());
        details.put("reason", metadata.reason());
        details.put("operatorId", metadata.operatorId().toString());
        details.put("idempotencyKey", metadata.idempotencyKey());
        details.put("subscriptionEntitled", subscriptionEntitled);
        details.put("maxAdjustmentMonths", MAX_ADJUSTMENT_MONTHS);
        return details;
    }

    /**
     * 撤销事件的审计详情。
     *
     * @param adjustment 被撤销的调整（撤销前快照）
     * @param reason 撤销原因
     * @param revokedAt 撤销时刻
     * @return 结构化详情
     */
    private Map<String, Object> revokeDetails(TenantResourcePackage adjustment, String reason,
                                              Instant revokedAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("adjustmentId", adjustment.id().toString());
        details.put("dimensionCode", adjustment.dimensionCode());
        details.put("amount", adjustment.amount());
        details.put("startsAt", adjustment.startsAt().toString());
        details.put("endsAt", adjustment.endsAt().toString());
        details.put("previousStatus", adjustment.status().name());
        details.put("reason", reason.trim());
        details.put("revokedAt", revokedAt.toString());
        if (adjustment.adjustment() != null) {
            details.put("grantReason", adjustment.adjustment().reason());
            details.put("grantedBy", adjustment.adjustment().operatorId().toString());
            details.put("idempotencyKey", adjustment.adjustment().idempotencyKey());
        }
        return details;
    }
}
