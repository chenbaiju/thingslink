package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 管理端精确应用版本读取在单一数据库观察中的封闭分类。
 *
 * <p>S12-1e2b要求先区分不可见应用，再区分可见应用下的版本缺失；用本结果代替两次查询，
 * 避免READ COMMITTED下并发软删让同一请求在60030与60048之间漂移。发布事务继续使用
 * {@link ApplicationRepository#findVersion}，不能借管理读取结果绕过其既有锁内流程。</p>
 *
 * @param status 单条查询确定的可见性分类
 * @param version 仅FOUND时存在的完整不可变版本
 */
public record ApplicationVersionLookupResult(Status status, ApplicationVersion version) {

    /** 精确约束成功与失败分类携带的版本事实。 */
    public ApplicationVersionLookupResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.FOUND) != (version != null)) {
            throw new IllegalArgumentException("应用版本查询分类与版本事实不一致");
        }
    }

    /** @return 成功时的完整不可变版本；其他分类为空 */
    public Optional<ApplicationVersion> foundVersion() {
        return Optional.ofNullable(version);
    }

    /**
     * 构造精确版本已找到的结果。
     *
     * @param version 单条查询返回的完整版本
     * @return FOUND结果
     */
    public static ApplicationVersionLookupResult found(ApplicationVersion version) {
        return new ApplicationVersionLookupResult(Status.FOUND, Objects.requireNonNull(version, "version"));
    }

    /** @return 应用不存在、跨项目或已软删除的结果 */
    public static ApplicationVersionLookupResult applicationNotFound() {
        return new ApplicationVersionLookupResult(Status.APPLICATION_NOT_FOUND, null);
    }

    /** @return 可见应用下版本不存在或归属不符的结果 */
    public static ApplicationVersionLookupResult versionNotFound() {
        return new ApplicationVersionLookupResult(Status.VERSION_NOT_FOUND, null);
    }

    /** 单条查询允许返回的完整分类。 */
    public enum Status {
        /** 应用与精确版本均可见。 */
        FOUND,
        /** 应用不存在、跨项目或已软删除。 */
        APPLICATION_NOT_FOUND,
        /** 应用可见，但精确版本不存在或不属于它。 */
        VERSION_NOT_FOUND
    }
}
