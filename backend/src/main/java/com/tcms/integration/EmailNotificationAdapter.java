package com.tcms.integration;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The instance's SMTP relay, to the addresses on the channel.
 *
 * <h2>The kind that disagrees with the other two about what a channel even is</h2>
 *
 * <p>Slack and Teams post a document to a URL that identifies a room. This one has no room: it has
 * a list of people, no credential of its own, and a relay that belongs to the deployment rather
 * than to the company. That disagreement is why it is in the first three — it is the
 * implementation most likely to expose an assumption baked into {@link NotificationChannelPort}.
 *
 * <h2>It throws when there is no relay, where the invitation mailer swallows</h2>
 *
 * <p>{@code identity.SmtpInvitationMailer} deliberately survives a dead relay, because the company
 * it was announcing is already provisioned and the operator has the link on their console — a mail
 * failure there must not undo a provisioning run. Nothing of that applies here. A notification has
 * no other delivery route, and it sits on an outbox row that will retry it on a backoff and
 * eventually mark it visibly failed. Swallowing would write {@code SENT} into the delivery log for
 * a message that does not exist, which is the one outcome an operator cannot recover from.
 *
 * <p>{@link JavaMailSender} is taken as a provider because Boot only auto-configures one when
 * {@code spring.mail.host} is set — {@code MailConfiguration}'s constraint, met differently: that
 * class picks a bean at startup, this one reports at delivery time, so an install with no relay
 * gets a delivery log entry naming the missing configuration rather than a channel that silently
 * never fires.
 */
@Component
class EmailNotificationAdapter implements NotificationChannelPort {

    private final ObjectProvider<JavaMailSender> senders;
    private final String fromAddress;

    EmailNotificationAdapter(
            ObjectProvider<JavaMailSender> senders, NotificationMailProperties properties) {
        this.senders = senders;
        this.fromAddress = properties.fromAddress();
    }

    @Override
    public NotificationChannelKind kind() {
        return NotificationChannelKind.EMAIL;
    }

    @Override
    public void deliver(RenderedNotification notification, ChannelConfig config) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException(
                    "No SMTP relay is configured (spring.mail.host is unset), so this email "
                            + "channel cannot deliver. Configure a relay or disable the channel.");
        }

        MimeMessage message = sender.createMimeMessage();
        try {
            // true: multipart, so the plain part and the HTML part are alternatives of one
            // message rather than two messages or one with an attachment.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(config.recipients().toArray(String[]::new));
            helper.setSubject(notification.title());
            // Plain first, HTML second — the order is the MIME preference order, and a client
            // that renders no HTML shows the first part. Both are generated from one
            // RenderedNotification, so they cannot say different things.
            helper.setText(notification.asPlainText(), html(notification));
        } catch (MessagingException malformed) {
            throw new IllegalStateException(
                    "The notification email could not be composed", malformed);
        }
        sender.send(message);
    }

    /**
     * The HTML alternative.
     *
     * <p>Hand-escaped rather than templated: a template engine is a dependency and a second file
     * for four elements, and every value here is user input — a run named {@code <b>now</b>} must
     * arrive as those characters and not as markup.
     */
    private static String html(RenderedNotification notification) {
        StringBuilder body = new StringBuilder("<h2>").append(escape(notification.title()))
                .append("</h2><p>").append(escape(notification.summary())).append("</p>");
        if (!notification.facts().isEmpty()) {
            body.append("<ul>");
            for (RenderedNotification.Fact fact : notification.facts()) {
                body.append("<li><strong>").append(escape(fact.label())).append("</strong>: ")
                        .append(escape(fact.value())).append("</li>");
            }
            body.append("</ul>");
        }
        return body.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
