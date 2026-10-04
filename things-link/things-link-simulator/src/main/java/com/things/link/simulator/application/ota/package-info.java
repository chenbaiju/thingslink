/**
 * 设备侧 OTA 执行切片 S13-4d-1：可独立测试、可确定性重放的设备状态机。
 *
 * <h2>本片交付什么</h2>
 * <p>设备在拿到平台下载授权之后应该怎么下载、校验、刷写、重启、健康检查与提交，以及在这些过程中
 * 掉电、首错、续传被拒、取消迟到时如何<b>如实</b>停下来。阶段名与原因码复用 ADR0128/0129/0131/0132/0137
 * 的既有文本，因此后续 MQTT 接线只需要搬运字段，不需要再设计一套设备状态。</p>
 *
 * <h2>本片刻意不做什么（为什么）</h2>
 * <ul>
 *   <li><b>不接 MQTT、不接平台派发。</b>真实的 OTA 派发需要受控签名器签出 READY 固件才会产生批次、
 *       作业、下载授权与提交许可；模拟器不持有签名私钥，本地无法产生真实授权链。任何「假装已经派发」
 *       的实现都会让联调把模拟器自造的字段当成平台事实。</li>
 *   <li><b>不声称硬件证据。</b>真实验签、槽位擦写、看门狗、受保护计数器与原子提交是 G3 硬件验收内容。
 *       本片只产出阶段序列与 SHA-256 事实，健康窗口与提交许可校验必须由后续平台的 permit 驱动。</li>
 *   <li><b>不发明摘要算法。</b>完整校验使用 artifact 的完整 SHA-256，而不是自定义的链式摘要；
 *       跨进程恢复靠「暂存区重放前缀 + 日志里的前缀摘要」而不是不可序列化的 {@code MessageDigest} 状态。</li>
 * </ul>
 *
 * <p>端口有三个：{@link com.things.link.simulator.application.ota.OtaArtifactSource}（取字节）、
 * {@link com.things.link.simulator.application.ota.OtaClock}（时间与退避）、
 * {@link com.things.link.simulator.application.ota.OtaStateJournal}（耐久日志）。生产实现分别位于
 * {@code com.things.link.simulator.infrastructure.ota}。</p>
 */
package com.things.link.simulator.application.ota;
