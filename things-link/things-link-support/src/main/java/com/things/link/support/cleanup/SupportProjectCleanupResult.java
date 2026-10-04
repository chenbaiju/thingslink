package com.things.link.support.cleanup;

/**
 * ADR0090：技术清理结果不引用project类型，避免support反向依赖业务模块。
 * @param deletedRows 本次真实删除行数
 * @param complete 本项目技术事实是否已空
 * @param blockedReason 稳定等待原因，正常批次为空
 */
public record SupportProjectCleanupResult(int deletedRows, boolean complete, String blockedReason) { }
