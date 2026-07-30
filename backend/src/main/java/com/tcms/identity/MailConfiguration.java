package com.tcms.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @Bean
    @ConditionalOnProperty(name = "spring.mail.host")
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
     */
    @Bean
    @ConditionalOnMissingBean(InvitationMailer.class)
    public InvitationMailer unconfiguredInvitationMailer() {
        Logger log = LoggerFactory.getLogger(MailConfiguration.class);
        return message -> log.warn(
                "No SMTP relay is configured (spring.mail.host is unset), so the invitation for "
                        + "{} to '{}' was NOT emailed. The invitation is valid — deliver the "
                        + "activation link printed by provision-company by hand, or configure a "
                        + "relay.",
                message.toEmail(), message.companyName());
    }
}
