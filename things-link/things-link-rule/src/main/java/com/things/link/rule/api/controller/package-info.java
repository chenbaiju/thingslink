/**
 * 规则模块的 HTTP Controller。
 *
 * <p>本包只放 REST 入口：负责参数绑定、HTTP 状态码、OpenAPI 注解与调用
 * {@code application} 用例。业务规则、事务编排和持久化访问都不在这里写，
 * 否则 Controller 会变成第二套服务层。
 */
package com.things.link.rule.api.controller;
