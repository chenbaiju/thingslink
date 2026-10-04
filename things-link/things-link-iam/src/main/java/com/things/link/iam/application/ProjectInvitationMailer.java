package com.things.link.iam.application;

import com.things.link.project.application.ProjectInvitationDeliveryService;
import com.things.link.project.application.ProjectInvitationDispatchRequested;
import com.things.link.support.notification.mail.LoggingMailSender;
import com.things.link.support.notification.mail.MailExecutorConfiguration;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.support.notification.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 提交后异步发送；不把开发日志邮件视为送达，不把秘密放进异常日志。 */
@Component
@EnableConfigurationProperties(ConsoleProperties.class)
public class ProjectInvitationMailer {
    private static final Logger log = LoggerFactory.getLogger(ProjectInvitationMailer.class);
    private final ProjectInvitationDeliveryService deliveries;
    private final MailSender sender;
    private final String baseUrl;
    public ProjectInvitationMailer(ProjectInvitationDeliveryService deliveries, MailSender sender, ConsoleProperties properties) {
        this.deliveries = deliveries; this.sender = sender; this.baseUrl = properties.baseUrl();
    }
    @Async(MailExecutorConfiguration.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ProjectInvitationDispatchRequested event) {
        try {
            var prepared = deliveries.prepare(event.invitationId(), event.revision());
            if (prepared.isEmpty()) return;
            if (sender instanceof LoggingMailSender) {
                deliveries.complete(event.invitationId(), event.revision(), false);
                log.warn("邀请邮件未配置真实发送器 invitationId={} revision={}", event.invitationId(), event.revision());
                return;
            }
            var value = prepared.orElseThrow();
            // Console是hash路由，问号仍在fragment中；服务端访问日志不会收到码。
            String link = baseUrl + "/#/auth/project-invitation/" + event.invitationId() + "?code=" + value.code();
            sender.send(MailMessage.text(value.email(), "项目协作邀请", """
                    你收到一份项目协作邀请。请打开下面的链接查看邀请，使用此邮箱注册并完成邮箱验证，
                    登录后明确接受才会加入项目。已有账号可以直接登录，在个人中心查看待处理邀请。

                    %s

                    邀请有效期至 %s。不要转发此链接；如果你不认识邀请方，忽略本邮件即可。
                    """.formatted(link, value.expiresAt())));
            deliveries.complete(event.invitationId(), event.revision(), true);
        } catch (RuntimeException failure) {
            // SMTP异常可能包含地址或正文，故只记稳定身份，不输出异常文本/堆栈。
            log.warn("邀请邮件投递或结果登记失败 invitationId={} revision={}", event.invitationId(), event.revision());
            try { deliveries.complete(event.invitationId(), event.revision(), false); }
            catch (RuntimeException unavailable) {
                log.warn("邀请邮件失败状态登记不可用 invitationId={} revision={}", event.invitationId(), event.revision());
            }
        }
    }
}
