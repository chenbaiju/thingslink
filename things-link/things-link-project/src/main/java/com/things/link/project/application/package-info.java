/**
 * 租户与项目（租户、项目、成员、配额与计量） —— 应用层：用例与事务边界。
 *
 * <p><b>本包的 public 类型是本模块唯一的对外契约面</b>（架构文档 10.4 规则 2）。
 * 其他模块只能引用这里的类型，不能碰 {@code domain}、{@code infrastructure}、
 * {@code api}。跨模块的领域事件类型也放在本包下。
 *
 * <p>事务边界在本层，不在 Controller 也不在 domain。
 */
package com.things.link.project.application;
