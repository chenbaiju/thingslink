package com.things.link.project.domain;

/**
 * 资源包的来源（S14-4a，S14-4b 预留）。
 *
 * <p>{@link #PURCHASE} 是客户经模拟订单购买的包，必须带来源订单；{@link #OPERATION_ADJUSTMENT}
 * 是 S14-4b 的有限期人工调整，没有订单但必须有操作人、原因与有效期。两者在合成上完全同权
 * （都是「基础 + 求和」的一项），但在追溯与审计上必须可区分，因此来源是包行的显式列而不是推断。
 */
public enum ResourcePackageSource {

    /** 客户购买（S14-4a）。 */
    PURCHASE,

    /** 运营有限期人工调整（S14-4b 预留）。 */
    OPERATION_ADJUSTMENT
}
