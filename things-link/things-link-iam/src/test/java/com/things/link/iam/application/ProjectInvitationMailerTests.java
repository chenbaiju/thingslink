package com.things.link.iam.application;
import com.things.link.project.application.ProjectInvitationDeliveryService;
import com.things.link.project.application.ProjectInvitationDispatchRequested;
import com.things.link.support.notification.mail.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 模板、安全日志发送器和失败恢复的边界；真实事务监听由集成用例验证。 */
class ProjectInvitationMailerTests {
    private final ProjectInvitationDeliveryService delivery = mock(ProjectInvitationDeliveryService.class);
    private final MailSender sender = mock(MailSender.class);
    private final ProjectInvitationDispatchRequested event = new ProjectInvitationDispatchRequested(UUID.randomUUID(), 2);
    private ProjectInvitationMailer mailer(MailSender sender) {
        return new ProjectInvitationMailer(delivery, sender, new ConsoleProperties("https://console.example.test/"));
    }
    private void pending() {
        when(delivery.prepare(event.invitationId(), 2)).thenReturn(Optional.of(
            new ProjectInvitationDeliveryService.Delivery("recipient@example.test", "c".repeat(43), Instant.now().plusSeconds(3600))));
    }
    @Test void rendersOnlyConfiguredOriginAndKeepsCodeInFragment() {
        pending(); mailer(sender).onRequested(event);
        var capture = ArgumentCaptor.forClass(MailMessage.class); verify(sender).send(capture.capture());
        var mail = capture.getValue();
        assertThat(mail.to()).isEqualTo("recipient@example.test");
        assertThat(mail.text()).contains("https://console.example.test/#/auth/project-invitation/" + event.invitationId() + "?code=" + "c".repeat(43));
        verify(delivery).complete(event.invitationId(), 2, true);
    }
    @Test void expiredOrSupersededDispatchDoesNotSend() {
        when(delivery.prepare(event.invitationId(), 2)).thenReturn(Optional.empty());
        mailer(sender).onRequested(event); verifyNoInteractions(sender);
        verify(delivery, never()).complete(any(), anyLong(), anyBoolean());
    }
    @Test void absentSmtpFailsWithoutPrintingInvitationToLoggingSender() {
        pending(); var logging = spy(new LoggingMailSender()); mailer(logging).onRequested(event);
        verify(logging, never()).send(any()); verify(delivery).complete(event.invitationId(), 2, false);
    }
    @Test void smtpFailureDoesNotEscapeAndRecordsRecoverableFailure() {
        pending(); doThrow(new IllegalStateException("transport failure")).when(sender).send(any());
        assertThatCode(() -> mailer(sender).onRequested(event)).doesNotThrowAnyException();
        verify(delivery).complete(event.invitationId(), 2, false);
    }
}
