package com.things.link.device.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 不可变物模型的顶层属性运行描述。
 *
 * @param propertyKey 属性键
 * @param dataType 属性数据类型：数值、文本、开关、枚举、对象或列表
 * @param unit 可选单位
 * @param minimumValue 可选数值下界
 * @param maximumValue 可选数值上界
 * @param enumOptions ENUM选项；其他类型为空
 * @param onLabel SWITCH开启文案
 * @param offLabel SWITCH关闭文案
 */
public record RuntimePropertyDescription(
        String propertyKey,
        String dataType,
        String unit,
        BigDecimal minimumValue,
        BigDecimal maximumValue,
        List<String> enumOptions,
        String onLabel,
        String offLabel) {

    /** 复制枚举集合并拒绝缺少属性身份。 */
    public RuntimePropertyDescription {
        Objects.requireNonNull(propertyKey, "propertyKey");
        Objects.requireNonNull(dataType, "dataType");
        enumOptions = enumOptions == null ? null : List.copyOf(enumOptions);
    }
}
