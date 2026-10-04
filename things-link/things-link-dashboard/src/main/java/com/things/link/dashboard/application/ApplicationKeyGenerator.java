package com.things.link.dashboard.application;

/** 为应用目录生成不可修改的公开定位符。 */
public interface ApplicationKeyGenerator {

    /**
     * 生成一个新的128-bit随机应用定位符候选。
     *
     * <p>候选仍须由数据库全局唯一索引仲裁；本端口不把随机性概率冒充唯一性保证。</p>
     *
     * @return {@code app_}加32位小写十六进制文本
     */
    String generate();
}
