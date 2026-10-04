package com.things.link.dashboard.application.publication;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 同一实际宿主制品的不可变资格快照。
 *
 * <p>该有限投影的存在前提是所属适配器已核验完整HostDescriptor的artifact、application-format、
 * manifest、资源实际字节及全部冻结字段；消费方不得从任意配置直接拼装本值冒充受管宿主。</p>
 *
 * @param formatVersion 宿主描述符格式版本
 * @param hostVersion 当前宿主SemVer
 * @param supportedApplicationFormats 支持的应用声明格式集合
 * @param supportedSchemas 支持的Dashboard Schema版本集合
 * @param componentVersions 按组件kind索引的精确实现版本
 * @param resourceDigests 按内置资源ID索引的精确字节摘要
 */
public record DashboardHostQualificationDescriptor(
        String formatVersion, String hostVersion, Set<String> supportedApplicationFormats,
        Set<String> supportedSchemas,
        Map<DashboardPublicationEligibilityRequirement.ComponentKind, String> componentVersions,
        Map<String, String> resourceDigests) {

    /** 防御复制宿主注册集合，具体合同由资格服务在同一快照上统一核验。 */
    public DashboardHostQualificationDescriptor {
        Objects.requireNonNull(formatVersion, "formatVersion");
        Objects.requireNonNull(hostVersion, "hostVersion");
        supportedApplicationFormats = Set.copyOf(supportedApplicationFormats);
        supportedSchemas = Set.copyOf(supportedSchemas);
        componentVersions = Map.copyOf(componentVersions);
        resourceDigests = Map.copyOf(resourceDigests);
    }
}
