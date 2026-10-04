package com.things.link.project.domain;

/**
 * 项目区域目录项。
 *
 * <p>这里的「区域」实际是项目最终落点的<b>可用区</b>，例如 {@code sh-1}。
 * 官方控制台还有上一级「地域」（北京、上海、广州、香港等），因此本对象同时保留
 * {@code areaCode}/{@code areaName} 供前端分组展示。
 *
 * @param code                   可用区编码，也是 {@code sys_project.region} 的值
 * @param name                   可用区展示名
 * @param areaCode               地域编码
 * @param areaName               地域展示名
 * @param enabled                是否可见
 * @param projectCreationEnabled 是否允许新建项目选择
 * @param displayOrder           展示顺序
 */
public record ProjectRegion(
        String code,
        String name,
        String areaCode,
        String areaName,
        boolean enabled,
        boolean projectCreationEnabled,
        int displayOrder) {
}
