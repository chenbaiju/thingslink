package com.things.link.device.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 不可变物模型版本的一项顶层属性资格事实。
 *
 * @param propertyKey 顶层属性键
 * @param dataType 冻结数据类型
 * @param minimumValue NUMBER属性可选最小值
 * @param maximumValue NUMBER属性可选最大值
 */
public record ThingModelPropertyFacts(
        String propertyKey, DataType dataType, BigDecimal minimumValue, BigDecimal maximumValue) {

    /** 冻结数据库投影；数值量程是否完备由具体消费合同裁决。 */
    public ThingModelPropertyFacts {
        Objects.requireNonNull(propertyKey, "propertyKey");
        Objects.requireNonNull(dataType, "dataType");
    }

    /** 物模型顶层属性的冻结类型闭集。 */
    public enum DataType {
        /** 数值。 */ NUMBER,
        /** 文本。 */ TEXT,
        /** 开关。 */ SWITCH,
        /** 枚举。 */ ENUM,
        /** 对象。 */ OBJECT,
        /** 列表。 */ LIST
    }
}
