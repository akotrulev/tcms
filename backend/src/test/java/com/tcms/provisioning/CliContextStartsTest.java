package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The operator CLI boots.
 *
 * <p><strong>This test exists because the suite could not see the defect that made it
 * necessary.</strong> The same jar is the server and the CLI, and
 * {@code TcmsApplication} runs {@code provision-company} with
 * {@code WebApplicationType.NONE}. In that context {@code HttpSecurity} does not exist —
 * so the moment {@code SecurityConfiguration} arrived, its {@code filterChain} bean could
 * not be satisfied and the <em>entire context</em> failed to start. Provisioning died
 * before it reached a database, on an instance that had just been deployed.
 *
 * <p>Every other test here boots a web context, which is why 165 green tests said nothing
 * about it. It was found by running the CLI against the live compose stack — the project's
 * own lesson 5, again: the gates prove it compiles, not that it works.
 *
 * <p>{@code webEnvironment = NONE} reproduces the CLI's arrangement exactly. The database
 * properties come from {@link IntegrationTestBase}; only the context type differs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("The operator CLI's non-web context")
class CliContextStartsTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Test
    @DisplayName("starts, and provisioning is usable in it")
    void theContextStarts() {
        // Reaching this method at all is most of the assertion: a context that cannot be
        // built fails the test before any body runs. The call proves the CLI's actual
        // dependency resolved rather than merely that some context exists.
        assertThat(provisioning).isNotNull();
        assertThat(provisioning.provision(ProvisionCompanyRequest.of(
                "cli-nonweb", "CLI Non-Web Ltd", "cli@nonweb.test", "CLI Admin"))
                .invitationLink())
                .as("the CLI's whole job — a company with a way into it")
                .isPresent();
    }

    @Test
    @DisplayName("builds no filter chain, because there are no requests to filter")
    void noSecurityFilterChainIsBuilt() {
        // The fix is a condition, not a removal: the chain must still exist for the server.
        // Asserting its absence HERE is what stops the condition being "corrected" into
        // something unconditional later, which would restore the failure.
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
