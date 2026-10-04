package com.things.link.device.application;

/** App控制目录投影；Schema保留原JSON文本，不能冒称已绑定后续受理版本。
 * @param commandKey 命令键 @param name 名称 @param description 说明
 * @param inputSchema 输入Schema @param outputSchema 输出Schema @param timeoutSeconds 超时秒数
 */
public record AppCommandDefinition(String commandKey, String name, String description,
                                   String inputSchema, String outputSchema, int timeoutSeconds) { }
