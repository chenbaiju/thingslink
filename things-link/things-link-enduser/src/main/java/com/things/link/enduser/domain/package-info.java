/**
 * 终端用户与应用域 —— 领域层：聚合、值对象、仓储契约（本包为模块内部，禁止跨模块引用）。
 *
 * <p>终端用户是与控制台账号分离的第二类身份（ADR 0035）：app_user 是租户级登录身份，
 * app_user_role / app_user_device 是项目事实。
 */
package com.things.link.enduser.domain;
