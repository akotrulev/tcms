package com.tcms.identity;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends the invitation over SMTP. Registered only when {@code spring.mail.host} is set —
 * see {@link MailConfiguration}.
 *
 * <h2>Plain text, deliberately</h2>
 *
 * <p>No HTML and no template engine. An invitation is one sentence and one link; HTML would
 * add a templating dependency, a second body to keep in step, and the class of mail-client
 * rendering bug that turns a link into an unclickable string. Plain text also survives the
 * aggressive link-rewriting some corporate gateways apply to HTML mail — and a rewritten
 * single-use token is an invitation that is spent before its owner clicks it.
 */
public class SmtpInvitationMailer implements InvitationMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpInvitationMailer.class);

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final JavaMailSender sender;
    private final String fromAddress;

    public SmtpInvitationMailer(JavaMailSender sender, String fromAddress) {
        this.sender = sender;
        this.fromAddress = fromAddress;
    }

    /** This bean exists only when {@code spring.mail.host} is set, so: always. */
    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public void send(InvitationMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(fromAddress);
        mail.setTo(message.toEmail());
        mail.setSubject("You have been invited to " + message.companyName());
        mail.setText(body(message));

        try {
            sender.send(mail);
            // The recipient and the company, never the link. A log line is read by people
            // who are not the invitee, and this one would be a working credential.
            log.info("Invitation email sent to {} for company '{}'",
                    message.toEmail(), message.companyName());
        } catch (MailException e) {
            // Swallowed on purpose — see InvitationMailer#send. The company is already
            // provisioned and the link is already on the operator's console; failing here
            // would turn a mail problem into a provisioning failure.
            log.error("Could not send the invitation email to {} for company '{}'. The company "
                            + "IS provisioned and the invitation IS valid — give the invitee the "
                            + "activation link printed by provision-company instead.",
                    message.toEmail(), message.companyName(), e);
        }
    }

    private String body(InvitationMessage message) {
        return """
                Hello %s,

                You have been invited to %s.

                Open the link below to choose a password and activate your account:

                  %s

                The link works once and expires on %s. If it has already expired, ask
                whoever invited you to issue a new one.

                If you were not expecting this invitation, you can ignore this message —
                nothing happens until the link is opened.
                """.formatted(
                message.toDisplayName(),
                message.companyName(),
                message.acceptUrl(),
                EXPIRY.format(message.expiresAt()));
    }
}
