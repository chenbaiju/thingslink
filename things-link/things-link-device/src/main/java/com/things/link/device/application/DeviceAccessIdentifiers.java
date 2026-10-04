package com.things.link.device.application;

import java.util.regex.Pattern;

/**
 * 项目短标识与设备短标识的安全字符集。
 *
 * <p>这两个标识同时出现在 MQTT Topic、Broker 认证用户名与三协议接入凭据中。若各协议各自维护一份
 * 字符集，同一设备会在 MQTT 上可认证、在新协议上被判非法（或反之），且差异只在接入边界暴露。
 * 因此字符集只能有一份实现：字母或数字起始，其后允许字母、数字、下划线与连字符，总长不超过 64。</p>
 */
public final class DeviceAccessIdentifiers {

    /** 冻结的标识符字符集，与 MQTT Topic 标识符完全一致。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    /** 工具类不允许实例化。 */
    private DeviceAccessIdentifiers() {
    }

    /**
     * 校验单个项目或设备短标识。
     *
     * @param identifier 待校验标识；空值一律非法
     * @return 是否符合冻结字符集
     */
    public static boolean isValid(String identifier) {
        return identifier != null && IDENTIFIER.matcher(identifier).matches();
    }
}
