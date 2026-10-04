/**
 * 终端用户与应用域 —— 应用层：用例与事务边界。
 *
 * <p><b>本包的 public 类型是本模块唯一的对外契约面</b>（架构文档 10.4 规则 2）。
 * 其他模块只能引用这里的类型，不能碰 {@code domain}、{@code infrastructure}、
 * {@code api}。
 *
 * <p>事务边界在本层。跨模块编排只依赖 dashboard、project 的 application 包；项目角色与路由继续通过
 * {@code ProjectService}，公开应用定位通过两个领域各自的最小运行端口，方向与 iam -> project 同向。
 */
package com.things.link.enduser.application;
