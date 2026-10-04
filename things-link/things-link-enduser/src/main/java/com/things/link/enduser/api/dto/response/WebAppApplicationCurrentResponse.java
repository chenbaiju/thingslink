package com.things.link.enduser.api.dto.response;

import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.enduser.application.CurrentWebAppApplication;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已认证WebApp当前应用的运行投影。
 *
 * <p>S12-2a4b只公开可信App身份、当前应用版本和已授权看板交集；内部tenant、完整应用快照、
 * 全局摘要及不可见看板均不进入响应。所有Long以十进制字符串输出，避免JavaScript数值精度损失。</p>
 *
 * @param identity 已认证App身份
 * @param application 当前应用公开身份
 * @param publicationRevision 当前发布代次十进制字符串
 * @param applicationVersionId 当前不可变应用版本ID
 * @param applicationVersionNumber 当前应用版本号十进制字符串
 * @param applicationFormatVersion 应用快照格式
 * @param hostCompatibility 宿主兼容半开区间
 * @param entryDashboardId 当前用户可见的原入口；无权读取原入口时为null
 * @param dashboards 按应用顺序过滤的可见精确看板引用
 */
@Schema(description = "WebApp当前应用运行描述")
public record WebAppApplicationCurrentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IdentityResponse identity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ApplicationResponse application,
        @Schema(type = "string", pattern = "^[1-9][0-9]*$", requiredMode = Schema.RequiredMode.REQUIRED)
        String publicationRevision,
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID applicationVersionId,
        @Schema(type = "string", pattern = "^[1-9][0-9]*$", requiredMode = Schema.RequiredMode.REQUIRED)
        String applicationVersionNumber,
        @Schema(allowableValues = "tc.application/v1", requiredMode = Schema.RequiredMode.REQUIRED)
        String applicationFormatVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HostCompatibilityResponse hostCompatibility,
        @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID entryDashboardId,
        @NotNull
        @ArraySchema(minItems = 1, maxItems = 5)
        List<PublishedDashboardReferenceResponse> dashboards) {

    /** 冻结嵌套对象和可见看板顺序。 */
    public WebAppApplicationCurrentResponse {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(publicationRevision, "publicationRevision");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(applicationVersionNumber, "applicationVersionNumber");
        Objects.requireNonNull(applicationFormatVersion, "applicationFormatVersion");
        Objects.requireNonNull(hostCompatibility, "hostCompatibility");
        dashboards = List.copyOf(dashboards);
    }

    /**
     * 从同一事务内完成身份、当前版本与grant交集的应用结果建立HTTP投影。
     *
     * @param current 已完成全部运行确权的应用结果
     * @return 不含内部tenant和全局快照字段的响应
     */
    public static WebAppApplicationCurrentResponse from(CurrentWebAppApplication current) {
        Objects.requireNonNull(current, "current");
        return new WebAppApplicationCurrentResponse(
                new IdentityResponse("APP", current.appUserId(), current.projectId()),
                new ApplicationResponse(current.applicationId(), current.appKey(), current.displayName()),
                Long.toString(current.publicationRevision()), current.applicationVersionId(),
                Long.toString(current.applicationVersionNumber()), current.applicationFormatVersion(),
                new HostCompatibilityResponse(
                        current.minimumHostVersionInclusive(), current.maximumHostVersionExclusive()),
                current.entryDashboardId(), current.dashboards().stream()
                .map(PublishedDashboardReferenceResponse::from)
                .toList());
    }

    /**
     * App access Bearer证明的可信实际身份。
     *
     * @param kind 固定身份类别APP
     * @param appUserId App JWT subject
     * @param projectId App JWT绑定项目ID
     */
    @Schema(description = "已认证App身份")
    public record IdentityResponse(
            @Schema(allowableValues = "APP", requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID appUserId,
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId) {

        /** 身份字段均来自已验证JWT，响应构造仍拒绝残缺投影。 */
        public IdentityResponse {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(appUserId, "appUserId");
            Objects.requireNonNull(projectId, "projectId");
            if (!"APP".equals(kind)) {
                throw new IllegalArgumentException("current身份类别必须为APP");
            }
        }
    }

    /**
     * 当前应用的公开身份与展示字段。
     *
     * @param id 稳定应用ID
     * @param appKey 创建后不可变的公开键
     * @param displayName 当前不可变版本的展示名称
     */
    @Schema(description = "当前可访问应用")
    public record ApplicationResponse(
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(pattern = "^app_[0-9a-f]{32}$", requiredMode = Schema.RequiredMode.REQUIRED) String appKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName) {

        /** 当前应用公开身份不得残缺。 */
        public ApplicationResponse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(appKey, "appKey");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 当前应用要求的宿主SemVer半开范围。
     *
     * @param minInclusive 最低宿主版本，包含边界
     * @param maxExclusive 最高宿主版本，不含边界
     */
    @Schema(description = "WebApp宿主兼容范围")
    public record HostCompatibilityResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String minInclusive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maxExclusive) {

        /** 宿主范围已经由发布合同验证，本投影继续拒绝null。 */
        public HostCompatibilityResponse {
            Objects.requireNonNull(minInclusive, "minInclusive");
            Objects.requireNonNull(maxExclusive, "maxExclusive");
        }
    }

    /**
     * 应用引用且当前用户有READ grant的精确看板版本。
     *
     * @param dashboardId 稳定看板ID
     * @param dashboardVersionId 应用固定的精确看板版本ID
     * @param dashboardVersionNumber 看板版本号十进制字符串
     * @param title 应用导航标题
     * @param schemaVersion 看板Schema格式
     * @param schemaDigestAlgorithm Schema摘要算法
     * @param schemaDigest Schema摘要
     * @param pages 有序页面导航
     */
    @Schema(description = "当前可见的精确看板版本引用")
    public record PublishedDashboardReferenceResponse(
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
            @Schema(type = "string", pattern = "^[1-9][0-9]*$", requiredMode = Schema.RequiredMode.REQUIRED)
            String dashboardVersionNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(allowableValues = "tc.dashboard/v1", requiredMode = Schema.RequiredMode.REQUIRED)
            String schemaVersion,
            @Schema(allowableValues = "PG_JSONB_TEXT_V1_SHA256", requiredMode = Schema.RequiredMode.REQUIRED)
            String schemaDigestAlgorithm,
            @Schema(pattern = "^[0-9a-f]{64}$", requiredMode = Schema.RequiredMode.REQUIRED)
            String schemaDigest,
            @NotNull
            @ArraySchema(minItems = 1, maxItems = 5)
            List<PageResponse> pages) {

        /** 冻结精确版本元数据与页面顺序。 */
        public PublishedDashboardReferenceResponse {
            Objects.requireNonNull(dashboardId, "dashboardId");
            Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
            Objects.requireNonNull(dashboardVersionNumber, "dashboardVersionNumber");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
            Objects.requireNonNull(schemaDigest, "schemaDigest");
            pages = List.copyOf(pages);
        }

        /** 从dashboard公开不可变引用显式投影，避免Jackson把领域实现细节自动暴露。 */
        private static PublishedDashboardReferenceResponse from(
                ApplicationPublishedDashboardReference reference) {
            return new PublishedDashboardReferenceResponse(
                    reference.dashboardId(), reference.dashboardVersionId(),
                    Long.toString(reference.dashboardVersionNumber()), reference.title(), reference.schemaVersion(),
                    reference.schemaDigestAlgorithm(), reference.schemaDigest(), reference.pages().stream()
                    .map(page -> new PageResponse(page.id(), page.title()))
                    .toList());
        }
    }

    /**
     * 看板Schema中的页面导航摘要。
     *
     * @param id 页面稳定键
     * @param title 页面展示标题
     */
    @Schema(description = "看板页面导航摘要")
    public record PageResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title) {

        /** 页面导航不得出现残缺字段。 */
        public PageResponse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }
}
