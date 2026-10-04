package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.ApplicationRuntimeLocation;
import com.things.link.dashboard.domain.ApplicationRuntimeRepository;
import com.things.link.dashboard.domain.PublishedApplicationRuntimeProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 向enduser运行编排公开应用的两阶段解析能力。
 *
 * <p>S12-2a2a要求无范围首跳与普通RLS重验共享调用方事务。调用方必须先调用
 * {@link #locateByAppKey(String)}，再以返回身份建立{@code TransactionLocalRlsScope}，最后调用
 * {@link #findCurrent(ApplicationRuntimeIdentity, String)}；任何阶段的空值都只表示确定不可用，数据库或
 * 持久正文异常保持原样传播。</p>
 */
@Service
public class ApplicationRuntimeResolutionService {

    /** ADR0096冻结的公开定位符语法，拒绝规范化、大小写转换或静默截断。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");

    /** 受限定位与普通RLS重验的独立持久端口。 */
    private final ApplicationRuntimeRepository repository;

    /**
     * 创建应用运行解析服务。
     *
     * @param repository 两阶段应用运行持久端口
     */
    public ApplicationRuntimeResolutionService(ApplicationRuntimeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 在没有可信项目范围时取得最小应用身份。
     *
     * @param appKey 规范公开定位符
     * @return 当前已发布且未软删应用的最小身份；确定不可用时为空
     * @throws IllegalArgumentException appKey不符合冻结语法
     * @throws IllegalStateException 调用方没有建立外层事务
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ApplicationRuntimeIdentity> locateByAppKey(String appKey) {
        requireAppKey(appKey);
        return repository.locateByAppKey(appKey).map(location -> new ApplicationRuntimeIdentity(
                location.tenantId(), location.projectId(), location.applicationId()));
    }

    /**
     * 在调用方已经建立可信项目RLS后重新确认当前发布版本的公开投影。
     *
     * @param identity 受限首跳返回且已用于建立RLS的身份
     * @param appKey 首跳使用的原始规范公开定位符
     * @return 当前发布事实；任一身份、删除状态或发布指针变化时为空
     * @throws IllegalArgumentException identity缺失或appKey不符合冻结语法
     * @throws IllegalStateException 调用方没有建立外层事务，或仓储返回身份漂移
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PublishedApplicationRuntime> findCurrent(
            ApplicationRuntimeIdentity identity, String appKey) {
        Objects.requireNonNull(identity, "identity");
        requireAppKey(appKey);
        return repository.findCurrent(
                        identity.tenantId(), identity.projectId(), identity.applicationId(), appKey)
                .map(projection -> toPublished(identity, appKey, projection));
    }

    /** 持久适配器若返回与查询键不同的事实即为内部不变量破坏，不能压缩成业务不可用。 */
    private static PublishedApplicationRuntime toPublished(
            ApplicationRuntimeIdentity expected,
            String expectedAppKey,
            PublishedApplicationRuntimeProjection projection) {
        ApplicationRuntimeIdentity actual = new ApplicationRuntimeIdentity(
                projection.tenantId(), projection.projectId(), projection.applicationId());
        if (!expected.equals(actual) || !expectedAppKey.equals(projection.appKey())) {
            throw new IllegalStateException("应用运行投影身份发生漂移");
        }
        return new PublishedApplicationRuntime(actual, projection.appKey(), projection.displayName());
    }

    /** 公开定位符是稳定路径身份，任何非规范输入都由上层映射参数错误，不能查询后伪装不存在。 */
    private static void requireAppKey(String appKey) {
        if (appKey == null || !APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("appKey必须是app_加32位小写十六进制");
        }
    }
}
