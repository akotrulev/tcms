package com.tcms.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the invitation mailer from the configuration that exists.
 *
 * <p>Boot only auto-configures a {@link JavaMailSender} when {@code spring.mail.host} is
 * set, so an unconditional dependency on it would make the application refuse to start on
 * an install with no relay — including every test that does not care about mail. The two
 * beans below make "no relay configured" a named, visible state instead.
 */
@Configuration
public class MailConfiguration {

    // Not @ConditionalOnProperty("spring.mail.host"): that matches a property that is
    // PRESENT, and an empty string is present. A compose file writing
    // SPRING_MAIL_HOST: ${SMTP_HOST:-} therefore selected the SMTP mailer with nowhere to
    // send, and every invitation produced a connection-refused stack trace instead of the
    // "no relay configured" warning that describes the actual situation. Measured against
    // the live stack, not reasoned about.
    @Bean
    @ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
    public InvitationMailer smtpInvitationMailer(
            JavaMailSender sender, InvitationProperties properties) {
        return new SmtpInvitationMailer(sender, properties.fromAddress());
    }

    /**
     * The fallback, and it is deliberately noisy.
     *
     * <p>WARN rather than INFO because an install in this state cannot onboard anyone by
     * email — the operator has to carry the link from the console by hand every time, and
     * nobody should discover that from a support ticket.
     *
     * <p>A named class rather than the lambda it used to be: {@link InvitationMailer} gained
     * {@code isConfigured()} and stopped being a functional interface, which is the point — the
     * "no relay" state is now something a caller can <em>ask about</em> and report, rather than
     * something only this log line knows.
     */
    @Bean
    @ConditionalOnMissingBean(InvitationMailer.class)
    public InvitationMailer unconfiguredInvitationMailer() {
        return new UnconfiguredInvitationMailer();
    }

    /** Logs loudly and delivers nothing. See {@link #unconfiguredInvitationMailer()}. */
    static final class UnconfiguredInvitationMailer implements InvitationMailer {

        private static final Logger log =
                LoggerFactory.getLogger(UnconfiguredInvitationMailer.class);

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public void send(InvitationMessage message) {
            log.warn(
                    "No SMTP relay is configured (spring.mail.host is unset), so the invitation "
                            + "for {} to '{}' was NOT emailed. The invitation is valid — deliver "
                            + "the activation link printed by provision-company by hand, or "
                            + "configure a relay.",
                    message.toEmail(), message.companyName());
        }
    }
}
