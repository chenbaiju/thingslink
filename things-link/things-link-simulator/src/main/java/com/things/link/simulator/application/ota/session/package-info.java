/**
 * 设备侧 OTA 会话（S13-4d-2a）：平台下行报文到设备状态机的路由与上行发布。
 *
 * <p>本包只做「解析—执行—编码—发布」的搬运，不验签、不发明证据、不声称平台已派发；
 * 真正的派发仍需受控签名器签出 READY 固件，安装前停止命令的耐久处理也尚未接线。</p>
 */
package com.things.link.simulator.application.ota.session;
