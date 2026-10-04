package com.things.link.dashboard.application.publication;

import java.util.Objects;

/**
 * 一次完整受管读取的能力与字节身份，不证明该目标已部署或已通过存量业务兼容预检。
 *
 * @param descriptor 同一次读取投影的完整能力
 * @param artifactDigest 实际ZIP的SHA-256
 * @param sourceDigest 制品收据声明的源码SHA-256，不是运行时对源码树的复验
 */
public record DashboardRegisteredHostQualification(
        DashboardHostQualificationDescriptor descriptor, String artifactDigest, String sourceDigest) {
    /** 保持非空、规范SHA-256格式；完整文件资格必须由端口实现证明。 */
    public DashboardRegisteredHostQualification {
        Objects.requireNonNull(descriptor, "descriptor");
        if (artifactDigest == null || !artifactDigest.matches("[a-f0-9]{64}")
                || sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("宿主字节身份必须是规范SHA-256");
        }
    }
}
