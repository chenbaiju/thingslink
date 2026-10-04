package com.things.link.project.domain.plan;

/**
 * 一个资源包对某维度的加数（S14-4a）。
 *
 * <p>它是仓库读取的原始投影：按 {@code (dimensionCode, unit, window)} 分组求和后的加数。
 * 是否参与某个产品修订版的合成，由 {@link ResourcePackageDimension#matches} 按「维度同名、
 * 单位同值、窗口同值」判定；不同单位的加数不得相加，因此加数必须携带单位与窗口。
 *
 * @param dimensionCode 冻结维度编码
 * @param unit 额度单位
 * @param window 计量窗口
 * @param amount 该分组下所有有效包的额度合计，恒为正
 */
public record ResourcePackageAddition(String dimensionCode, String unit, String window, long amount) {

    /** 加数必须是可求和的确定正数。 */
    public ResourcePackageAddition {
        if (dimensionCode == null || unit == null || window == null) {
            throw new IllegalArgumentException("资源包加数的维度、单位与窗口都不得为空");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("资源包加数必须为正数");
        }
    }
}
