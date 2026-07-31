package com.tcms.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.InvitationMailer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The default deployment reports itself healthy.
 *
 * <p>It did not. {@code docker compose up} with no SMTP relay — the documented default,
 * the one every first run has — served {@code /actuator/health} as <strong>DOWN</strong>
 * on a fully working instance, because Spring's {@code mail} health indicator was trying
 * to reach a relay that was never configured. Handoff §20 found it by running the stack;
 * nothing in the suite could see it, and this class is why.
 *
 * <h2>Why no existing test caught it</h2>
 *
 * <p>{@code EndpointAccessTest} already asserts {@code /actuator/health} answers 200, and
 * it passes — because the default test context sets no {@code spring.mail.host} at all, so
 * no mail indicator is ever registered. {@code InvitationMailTest} sets a real host pointing
 * at GreenMail, so its indicator is genuinely UP. <strong>Neither context uses compose's
 * spelling of "no relay", which is the property present and empty</strong>, and that is the
 * only spelling that fails. The gap was a missing configuration, not a missing assertion.
 *
 * <h2>Why an empty host configures mail at all</h2>
 *
 * <p>Boot's {@code MailSenderAutoConfiguration} gates on
 * {@code @ConditionalOnProperty("spring.mail.host")}, which matches a property that is
 * <em>present</em> — and an empty string is present. So a {@code JavaMailSender} is built
 * with no host, {@code MailHealthContributorAutoConfiguration} registers an indicator
 * because that bean exists, and the indicator calls {@code testConnection()} against a host
 * that resolves to localhost. The aggregate takes the worst of its components, so one
 * unreachable relay reads as an unhealthy application.
 *
 * <p>This is exactly the trap {@link com.tcms.identity.MailConfiguration} already documents
 * one level down — §18 defect 3, where an empty host selected the SMTP mailer. That fix
 * covered this project's own bean selection. It could not cover Boot's.
 */
@AutoConfigureMockMvc
@DisplayName("Health report — an install with no mail relay is healthy, not DOWN")
class HealthReportTest extends IntegrationTestBase {

    /**
     * Compose's exact spelling of "no relay configured".
     *
     * <p>{@code compose.yaml} writes {@code SPRING_MAIL_HOST: ${SMTP_HOST:-}} and
     * {@code .env.example} ships {@code SMTP_HOST=} empty, so the container receives the
     * variable set to the empty string. Compose interpolation has no way to omit a key
     * conditionally, and neither does any other templated deployment — which is why this is
     * defended in the application rather than in the compose file.
     *
     * <p>The port is {@code .env.example}'s 1025 rather than the default 25, so the
     * {@code location} detail in a failing report reads {@code :1025} — the literal string
     * §20 captured from the live stack, tying this test to the observation it came from.
     */
    @DynamicPropertySource
    static void noMailRelayConfigured(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "");
        registry.add("spring.mail.port", () -> 1025);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private InvitationMailer mailer;

    @Test
    @DisplayName("the aggregate is UP, so nothing polling it depools a working instance")
    void aggregateHealthIsUpWithoutARelay() throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                        A DOWN aggregate is an instruction to an orchestrator: restart the \
                        container, take it out of the load balancer. Neither of those can \
                        reach an SMTP relay, so the signal is not merely noisy — it is wrong, \
                        and it fires on every default install.""")
                .isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("no mail contributor is registered, so no health poll dials the relay")
    void noMailHealthContributor() {
        // The aggregate assertion above is the symptom; this is the mechanism, and it is the
        // deterministic half. The status assertion depends on nothing happening to listen on
        // localhost:1025 during the run, which is true but not guaranteed. This one holds
        // whatever the machine is running.
        //
        // It also pins the second reason the indicator is unwanted even WITH a relay
        // configured: MailHealthIndicator calls testConnection() on every poll, which opens
        // and closes a real SMTP connection. At the image's 30s HEALTHCHECK interval that is
        // ~2,900 connections a day to a third-party relay, which is a shape relays rate-limit.
        assertThat(context.containsBean("mailHealthContributor"))
                .as("management.health.mail.enabled must stay false — see application.yml")
                .isFalse();
    }

    @Test
    @DisplayName("an empty host still builds a JavaMailSender — the canary for the workaround")
    void emptyHostStillTriggersBootsMailAutoConfiguration() {
        // Deliberately asserting the surprising thing rather than the desirable one.
        //
        // The fix disables a health indicator; it does NOT stop Boot treating an empty host
        // as configured, and the resulting JavaMailSender — pointed at nowhere — is still in
        // the context, unused. If a future Boot version tightens @ConditionalOnProperty to
        // reject a blank value, this fails, and that failure is the signal to delete the
        // workaround rather than a defect to repair.
        assertThat(context.getBeanNamesForType(JavaMailSender.class))
                .as("if this is now empty, Boot fixed the root cause and application.yml's "
                        + "management.health.mail.enabled=false is dead configuration")
                .isNotEmpty();

        // And the §18 fix still holds one level down: an empty host must not select SMTP.
        assertThat(mailer)
                .as("an empty host is not a relay — the invitation mailer must be the "
                        + "unconfigured fallback, or every invitation stack-traces")
                .isNotInstanceOf(com.tcms.identity.SmtpInvitationMailer.class);
    }
}
