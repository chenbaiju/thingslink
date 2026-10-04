package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 设备类型应用服务，负责项目成员校验、创建规则与事务边界。 */
@Service
public class DeviceTypeService {
    /** 产品与设备密钥均使用 256 位随机量，数据库只保存摘要。 */
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 设备类型仓储。 */ private final DeviceTypeRepository repository;
    /** 项目模块公开应用服务；跨模块不直接读取成员表。 */ private final ProjectService projectService;
    /** 发布类型时同步冻结 1.0.0 快照，避免新设备进入无版本数据面。 */
    private final ThingModelVersionRepository thingModelVersionRepository;
    /** ADR0057：草稿不意味着可破坏已建立的拓扑角色。 */
    private final DeviceTopologyRoleGuard roleGuard;
    /** 原生接入配置不能被后续类型编辑破坏。 */
    private final DeviceAccessTypeGuard accessTypes;

    /**
     * @param repository 设备类型仓储 @param projectService 项目成员关系查询服务
     * @param thingModelVersionRepository 物模型版本与初始绑定仓储
     */
    public DeviceTypeService(DeviceTypeRepository repository, ProjectService projectService,
                             ThingModelVersionRepository thingModelVersionRepository, DeviceTopologyRoleGuard roleGuard,
                             DeviceAccessTypeGuard accessTypes) {
        this.repository = repository; this.projectService = projectService;
        this.thingModelVersionRepository = thingModelVersionRepository;
        this.roleGuard = roleGuard; this.accessTypes = accessTypes;
    }

    /**
     * 列出当前账号可见的指定项目设备类型。
     * @param projectId 项目 ID
     * @return 设备类型列表
     */
    @Transactional(readOnly = true, timeout = 3)
    public List<DeviceType> list(UUID projectId) {
        requireMember(projectId);
        CursorPage<DeviceType> page = repository.search(projectId, null, 200);
        if (page.hasMore()) {
            throw new BusinessException(DeviceErrorCode.LEGACY_LIST_LIMIT_EXCEEDED);
        }
        return page.items();
    }

    /**
     * 键集分页读取项目设备类型。
     * @param projectId 项目 ID @param cursor 上一页游标 @param limit 单页数量
     * @return 稳定创建时间与 UUID 倒序的一页类型
     */
    @Transactional(readOnly = true, timeout = 3)
    public CursorPage<DeviceType> search(UUID projectId, String cursor, int limit) {
        requireMember(projectId);
        return repository.search(projectId, cursor, limit);
    }

    /**
     * 读取该类型按语义版本降序的最新已发布物模型版本。
     *
     * <p>OTA 固件草稿必须绑定一个物模型版本身份，而设备类型详情不携带版本身份，
     * 控制台因此需要一个只读入口。本方法不创建版本、不修改类型状态：没有已发布版本时
     * 按 fail-closed 报 30052，由发布流程而不是本读取补上事实。
     *
     * @param projectId 项目 ID
     * @param deviceTypeId 类型 ID
     * @return 最新不可变版本
     */
    @Transactional(readOnly = true, timeout = 3)
    public ThingModelVersion latestThingModelVersion(UUID projectId, UUID deviceTypeId) {
        requireMember(projectId);
        return thingModelVersionRepository.findLatest(projectId, deviceTypeId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_NOT_FOUND));
    }

    /**
     * 创建草稿设备类型；只有 OWNER/ADMIN 可以修改项目物模型。
     *
     * <p>G1-C1b 起不再随设备类型创建默认数据流：自定义数据流 V1 控制面已下线
     * （PS-039 TECHNICAL_DEFERRED），设备类型创建不得再写出永不被消费的 `dev_data_stream` 行。</p>
     *
     * @param projectId 项目 ID @param typeKey 稳定标识符 @param name 名称
     * @param deviceKind 分类 @param payloadProtocol 报文协议 @param networkType 通信方式
     * @return 新设备类型
     */
    @Transactional
    public DeviceType create(UUID projectId, String typeKey, String name,
                             DeviceType.DeviceKind deviceKind, DeviceType.PayloadProtocol payloadProtocol,
                             DeviceType.NetworkType networkType) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_CREATE_FORBIDDEN);
        }
        TenantScope scope = currentScope();
        requireCompatible(deviceKind, payloadProtocol);
        DeviceType type = new DeviceType(Uuid7.generate(), scope.tenantId(), projectId,
                typeKey.strip(), name.strip(), deviceKind, payloadProtocol, networkType,
                1, DeviceType.Status.DRAFT, null, null, Instant.now());
        try {
            repository.create(type);
        } catch (DuplicateKeyException exception) {
            // 由唯一约束仲裁而不是“先查后插”，否则并发请求会一起通过预检查。
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_KEY_CONFLICT);
        }
        return type;
    }

    /**
     * 修改草稿的基础信息；已发布类型是设备数据契约，只能在 S2-6 通过新版本演进。
     * @param projectId 项目 ID @param id 类型 ID @param typeKey 标识符 @param name 名称
     * @param deviceKind 分类 @param payloadProtocol 报文协议 @param networkType 通信方式 @return 更新后的类型
     */
    @Transactional
    public DeviceType update(UUID projectId, UUID id, String typeKey, String name,
                             DeviceType.DeviceKind deviceKind, DeviceType.PayloadProtocol payloadProtocol,
                             DeviceType.NetworkType networkType) {
        requireManager(projectId);
        DeviceType current = requireTypeForUpdate(projectId, id);
        requireDraft(current);
        requireCompatible(deviceKind, payloadProtocol);
        roleGuard.requireTypeRole(projectId, id, deviceKind);
        accessTypes.requireType(current.tenantId(), projectId, id, deviceKind, payloadProtocol);
        DeviceType updated = new DeviceType(current.id(), current.tenantId(), current.projectId(),
                typeKey.strip(), name.strip(), deviceKind, payloadProtocol, networkType,
                current.version(), current.status(), current.productKey(), current.productSecretHash(), current.createdAt());
        try {
            if (!DeviceTopologyRoleGuard.controlWrite(() -> repository.update(updated))) {
                throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND);
            }
        } catch (DuplicateKeyException exception) {
            // 更新标识符也由数据库唯一约束仲裁，避免并发重命名穿透“先查后改”。
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_KEY_CONFLICT);
        }
        return updated;
    }

    /**
     * 发布草稿；状态变为 PUBLISHED 后，物模型（属性/事件/命令/数据流）全部冻结，
     * 后续只能通过创建新版本来演进。
     * @param projectId 项目 ID @param id 类型 ID @return 发布后的类型
     */
    @Transactional
    public DeviceType publish(UUID projectId, UUID id) {
        requireManager(projectId);
        DeviceType current = requireTypeForUpdate(projectId, id);
        if (current.status() != DeviceType.Status.DRAFT) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE);
        }
        // 类型漂移必须在首次发布获取多设备锁之前拒绝，不能进入原有条件性锁环后才校验。
        roleGuard.requireTypeRole(projectId, id, current.deviceKind());
        accessTypes.requireType(current.tenantId(), projectId, id, current.deviceKind(), current.payloadProtocol());
        if (!repository.publish(projectId, id)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND);
        }
        // B-X1a/B-X1b 要求发布即形成不可变 1.0.0；同事务失败必须回滚类型状态，不能留下 PUBLISHED 但不可摄入的半成品。
        thingModelVersionRepository.createInitialFromDefinitions(current.tenantId(), projectId, id);
        return new DeviceType(current.id(), current.tenantId(), current.projectId(),
                current.typeKey(), current.name(), current.deviceKind(), current.payloadProtocol(),
                current.networkType(), current.version(), DeviceType.Status.PUBLISHED,
                current.productKey(), current.productSecretHash(), current.createdAt());
    }

    /**
     * 为已发布设备类型生成或轮换一型一密凭据。
     *
     * <p>产品密钥明文仅随本次返回；再次调用立即覆盖旧摘要，旧批次随后不能继续注册。
     * productKey 是 Topic 段而非秘密，首次生成后保持稳定，避免轮换密钥迫使固件改变 Topic。</p>
     *
     * @param projectId 项目 ID
     * @param id 设备类型 ID
     * @return 仅此一次携带产品密钥明文的结果
     */
    @Transactional
    public ProductCredential generateProductCredential(UUID projectId, UUID id) {
        requireManager(projectId);
        DeviceType current = requireTypeForUpdate(projectId, id);
        if (current.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE) {
            // 子设备无独立接入，一型一密产品凭据只属于可独立接入的类型（决策 2）。
            throw new BusinessException(DeviceErrorCode.SUB_DEVICE_CREDENTIAL_FORBIDDEN);
        }
        if (current.status() != DeviceType.Status.PUBLISHED) {
            throw new BusinessException(DeviceErrorCode.PRODUCT_CREDENTIAL_STATE_INVALID);
        }
        String productKey = current.productKey() == null ? randomIdentifier(12) : current.productKey();
        String plainSecret = randomHex(32);
        if (!repository.updateProductCredential(projectId, id, productKey, sha256(plainSecret))) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND);
        }
        return new ProductCredential(id, productKey, plainSecret);
    }

    /** @param deviceTypeId 设备类型 ID @param productKey 注册 Topic 产品段 @param productSecret 一次可见密钥 */
    public record ProductCredential(UUID deviceTypeId, String productKey, String productSecret) {
    }

    /** 生成只包含小写字母和数字的 Topic 安全标识。 */
    private static String randomIdentifier(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    /** 生成指定字节数的随机十六进制密钥。 */
    private static String randomHex(int bytes) {
        byte[] secret = new byte[bytes];
        RANDOM.nextBytes(secret);
        return HexFormat.of().formatHex(secret);
    }

    /** 对高熵随机密钥取固定长度摘要，明文永不落库。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }

    /**
     * 软删除草稿；物模型子表落地后由外键与同一事务共同保证聚合完整性。
     * @param projectId 项目 ID @param id 类型 ID
     */
    @Transactional
    public void delete(UUID projectId, UUID id) {
        requireManager(projectId);
        DeviceType current = requireTypeForUpdate(projectId, id);
        requireDraft(current);
        roleGuard.requireTypeRole(projectId, id, null);
        accessTypes.requireType(current.tenantId(), projectId, id, null, null);
        if (!DeviceTopologyRoleGuard.controlWrite(() -> repository.softDelete(projectId, id))) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND);
        }
    }

    /** @param projectId 项目 ID @param id 类型 ID @return 未删除类型 */
    private DeviceType requireType(UUID projectId, UUID id) {
        return repository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
    /** @param projectId 项目 ID @param id 类型 ID @return 已锁定类型 */
    private DeviceType requireTypeForUpdate(UUID projectId, UUID id) {
        return repository.findByIdForUpdate(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** @param type 类型；发布状态必须保持不可变 */
    private static void requireDraft(DeviceType type) {
        if (type.status() != DeviceType.Status.DRAFT) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE);
        }
    }

    /**
     * 在应用层提前返回可读错误；数据库兼容矩阵约束仍负责拦截并发或绕过应用层的非法写入。
     * @param kind 设备分类 @param protocol 报文协议
     */
    private static void requireCompatible(DeviceType.DeviceKind kind, DeviceType.PayloadProtocol protocol) {
        boolean compatible = switch (kind) {
            case DIRECT -> protocol == DeviceType.PayloadProtocol.STANDARD
                    || protocol == DeviceType.PayloadProtocol.MODBUS_RTU_PASSTHROUGH
                    || protocol == DeviceType.PayloadProtocol.MODBUS_TCP_PASSTHROUGH;
            case GATEWAY -> protocol == DeviceType.PayloadProtocol.STANDARD_GATEWAY
                    || protocol == DeviceType.PayloadProtocol.MODBUS_RTU_CLOUD_GATEWAY;
            case SUB_DEVICE -> protocol == DeviceType.PayloadProtocol.STANDARD
                    || protocol == DeviceType.PayloadProtocol.MODBUS_RTU_PASSTHROUGH;
        };
        if (!compatible) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_PROTOCOL_INCOMPATIBLE);
        }
    }

    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN);
        }
    }

    /** @param projectId 项目 ID @return 当前账号的项目角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** @return 当前认证请求的租户范围 */
    private static TenantScope currentScope() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
    }
}
