package com.things.link.rule.application.engine;

/**
 * 规则节点之间的封闭关系；固定枚举避免租户配置制造无界路由标签。
 */
public enum RuleRelation {
    /** 条件成立，沿真分支继续。 */
    TRUE,
    /** 条件不成立，沿假分支继续。 */
    FALSE,
    /** 变换或补全成功，沿成功分支继续。 */
    SUCCESS,
    /** 节点遇到已分类的执行失败。 */
    FAILURE
}
