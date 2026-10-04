/**
 * 出站邮件设施：把「发一封邮件」这件事收敛成一个接口，屏蔽底层是 SMTP 还是日志。
 *
 * <h2>为什么放在 support 而不是 iam</h2>
 * 第一个用它的是 IAM 的邮箱验证，但它不是 IAM 的东西 —— S6 的告警通知渠道
 * （架构文档 9 节的 {@code notification_delivery}）要发同样的邮件。放进 iam 的话，
 * 到时候 notification 模块只能反向依赖一个业务模块，而依赖方向必须永远是
 * 「业务模块 → support」（本模块 package-info）。
 *
 * <h2>为什么要有 {@link com.things.link.support.notification.mail.MailSender} 这层接口</h2>
 * <b>不是为了「将来换服务商」</b> —— 换服务商只是改 {@code spring.mail.*} 几个配置，
 * 用不着抽象。真正的理由是<b>本地开发和测试不能碰真实邮箱</b>：没有这层接口，
 * 跑一次注册流程就要往真实 SMTP 发一封信，而个人邮箱的日发信量限制是几十到几百封，
 * 调试几轮就会把发信账号刷进限流。
 *
 * <h2>发送是同步阻塞的</h2>
 * {@code MailSender.send} 会一直阻塞到 SMTP 交互结束。<b>调用方必须自己解决两件事</b>：
 * <ul>
 *   <li><b>不要在 HTTP 请求线程里直接调</b> —— 一次 SMTP 握手是几百毫秒起步的网络往返，
 *       挂在注册接口上会直接体现为用户点了「注册」之后转圈</li>
 *   <li><b>不要在事务里调</b> —— 事务回滚不会把已经发出去的邮件收回来。
 *       正确姿势是 {@code @TransactionalEventListener(AFTER_COMMIT)}</li>
 * </ul>
 * 这两件事没有做进本包，是因为它们是<b>调用场景</b>的决定而不是邮件设施的决定：
 * 告警通知将来要走队列重试，和验证码「发失败就算了、让用户点重发」是完全不同的策略。
 * 把 {@code @Async} 焊死在这里会让后者也被迫走前者的路径。
 *
 * <p>IAM 的 EmailVerificationMailer 已在调用侧使用 AFTER_COMMIT 与专用异步执行器。
 * <p>S6-2 已确定告警通知使用 {@code alarm_notification_delivery + sys_outbox_event + Kafka} 的持久化意图；
 * S6-3 的邮件消费者必须从该事实链路读取，不能退回事务后内存回调。
 */
package com.things.link.support.notification.mail;
