/**
 * 租户与项目（租户、项目、成员、配额与计量） —— API 层：Controller 与 HTTP DTO。
 *
 * <p><b>本包的类型禁止被其他模块引用</b>（架构文档 10.4 规则 4）。这里的 DTO 是
 * HTTP 契约，不是内部契约；被其他模块复用后，「改一个 REST 字段」就会牵动
 * 另一个模块的内部逻辑。这条最常被忽略，也是 DTO 泛滥的根源。
 *
 * <p>Controller 不得直接调用 Repository（规则 1），只能调用同模块
 * {@code application} 层的用例 —— 直接调 Repository 会绕过事务边界。
 *
 * <p>权限校验必须在 Controller 与领域服务两层都有。只在 Controller 校验的话，
 * 将来内部调用绕过它就是越权。
 */
package com.things.link.project.api;
