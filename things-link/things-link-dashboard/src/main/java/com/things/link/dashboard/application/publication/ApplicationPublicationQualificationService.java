package com.things.link.dashboard.application.publication;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/** 对应用候选执行一次性受管宿主范围、Schema、组件和资源资格核验。 */
@Service
public class ApplicationPublicationQualificationService {

    /** 当前实际部署宿主的不可变注册事实端口。 */
    private final DashboardHostQualificationPort hostPort;

    /**
     * 创建应用宿主资格服务。
     *
     * @param hostPort 当前受管宿主的同一制品快照端口
     */
    public ApplicationPublicationQualificationService(DashboardHostQualificationPort hostPort) {
        this.hostPort = Objects.requireNonNull(hostPort, "hostPort");
    }

    /**
     * 核验候选全部宿主需求；端口异常原样传播，缺失或错配统一失败关闭。
     *
     * @param candidate 已绑定草稿和精确看板版本的规范候选
     * @return 仅当前调用成立、仍未写入发布事实的资格值
     */
    QualifiedApplicationPublicationCandidate qualify(ApplicationPublicationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        DashboardHostQualificationDescriptor host = hostPort.current()
                .orElseThrow(ApplicationPublicationQualificationService::hostUnavailable);
        require("tc.webapp-host/v1".equals(host.formatVersion())
                        && host.supportedApplicationFormats().equals(Set.of("tc.application/v1"))
                        && host.supportedSchemas().equals(Set.of("tc.dashboard/v1"))
                        && host.supportedSchemas().containsAll(candidate.requiredSchemas())
                        && !host.componentVersions().isEmpty()
                        && host.componentVersions().size() <= 10
                        && host.resourceDigests().size() <= 64
                        && hostVersionInRange(host.hostVersion(), candidate.minimumHostVersionInclusive(),
                                candidate.maximumHostVersionExclusive()),
                ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE);
        for (DashboardRequiredComponent component : candidate.requiredComponents()) {
            DashboardPublicationEligibilityRequirement.ComponentKind kind;
            try {
                kind = DashboardPublicationEligibilityRequirement.ComponentKind.valueOf(component.kind());
            } catch (IllegalArgumentException exception) {
                throw hostUnavailable();
            }
            require(component.componentVersion().equals(host.componentVersions().get(kind)),
                    ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE);
        }
        for (DashboardRequiredResource resource : candidate.requiredResources()) {
            require(resource.digest().equals(host.resourceDigests().get(resource.resourceId())),
                    ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE);
        }
        return new QualifiedApplicationPublicationCandidate(candidate);
    }

    /** 按三个有界整数段判断宿主版本是否处于应用草稿的半开区间。 */
    static boolean hostVersionInRange(String hostVersion, String minimum, String maximum) {
        try {
            int[] current = semanticVersion(hostVersion);
            return compareVersion(current, semanticVersion(minimum)) >= 0
                    && compareVersion(current, semanticVersion(maximum)) < 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 解析合同限定的无前导零三段SemVer，每段不得超过65535。 */
    private static int[] semanticVersion(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("宿主版本格式无效");
        }
        String[] parts = value.split("\\.");
        try {
            int[] version = new int[]{
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            for (int part : version) {
                if (part > 65_535) {
                    throw new IllegalArgumentException("宿主版本段超过65535");
                }
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("宿主版本段超出整数范围", exception);
        }
    }

    /** 逐段比较两个三段SemVer。 */
    private static int compareVersion(int[] left, int[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /** 条件不成立时抛出不含宿主实际内容的安全失败。 */
    private static void require(
            boolean condition, ApplicationPublicationQualificationException.Reason reason) {
        if (!condition) {
            throw new ApplicationPublicationQualificationException(reason);
        }
    }

    /** @return 宿主注册缺失或不兼容的统一失败 */
    private static ApplicationPublicationQualificationException hostUnavailable() {
        return new ApplicationPublicationQualificationException(
                ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE);
    }
}
