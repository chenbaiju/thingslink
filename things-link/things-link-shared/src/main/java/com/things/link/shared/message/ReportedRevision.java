package com.things.link.shared.message;

/** 已接受属性序号的规范正Long文本；不得转换为浮点数比较。 */
public final class ReportedRevision {
    /** 最大合法正Long的十进制文本。 */
    private static final String MAX = "9223372036854775807";
    /** 工具类不提供实例。 */
    private ReportedRevision() { }
    /** @param value 待验证文本 @return 是否为规范正Long */
    public static boolean isValid(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}")
                && (value.length() < 19 || value.compareTo(MAX) <= 0);
    }
    /** @param value 待验证文本 @return 原始规范文本，非法时抛异常 */
    public static String require(String value) {
        if (!isValid(value)) throw new IllegalArgumentException("reportedRevision须为规范正Long字符串");
        return value;
    }
}
