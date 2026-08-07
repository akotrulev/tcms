package com.tcms.provisioning;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code grant-instance-admin} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar grant-instance-admin --email=ops@example.test
 * }</pre>
 *
 * <p>Promotes an existing account to Instance Admin — the SYSTEM-scope operator role that
 * holds every permission in every company. This is the bootstrap path: the tier is seeded
 * and enforced but nothing in the application ever granted it, so before this command the
 * only way to create an operator was a hand-written {@code INSERT}.
 *
 * <p>Parsing and reporting only — the behaviour lives in {@link GrantInstanceAdminService},
 * the same split {@link ProvisionCompanyCommand} and {@link IssueInvitationCommand} use, so
 * an instance-admin API can reuse the service without the CLI.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — the account holds Instance Admin. Covers <em>both</em> a fresh grant and
 *       an account that already held it: this command is declarative, and re-running it is
 *       the supported way to be sure. See {@link GrantInstanceAdminResult} for why this
 *       diverges from {@code issue-invitation}, which exits non-zero on its no-op.</li>
 *   <li>{@code 2} — usage error: a missing or malformed argument</li>
 *   <li>{@code 1} — nothing was granted (no such account, or a deactivated one), or the
 *       transaction failed</li>
 * </ul>
 */
@Component
public class GrantInstanceAdminCommand implements ApplicationRunner {

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private static final String USAGE = """
            Usage:
              grant-instance-admin --email=<address>

            Grants SYSTEM-scope Instance Admin to an account that ALREADY EXISTS. This
            command grants a role; it does not create people. On a fresh instance, run
            provision-company first — that creates the company and its first administrator,
            and this promotes that address to operator.

            Instance Admin holds every permission in every company on this instance,
            including reach into client content. That reach is a disclosed, audited
            capability, not a side effect — grant it to people, deliberately, one at a time.

            Re-running is safe and exits 0: an account that already holds the role is
            reported as such and nothing is written.

            An account that has been invited but has not accepted yet CAN be granted. The
            grant stays inert until the account is ACTIVE and needs no second command.
            """;

    private static final Logger log = LoggerFactory.getLogger(GrantInstanceAdminCommand.class);

    private final GrantInstanceAdminService service;
    private final ApplicationContext context;

    public GrantInstanceAdminCommand(
            GrantInstanceAdminService service, ApplicationContext context) {
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Inert unless asked — an ApplicationRunner runs on every boot, including under
        // @SpringBootTest. See ProvisionCompanyCommand for why the argument check is the
        // mechanism rather than a profile exclusion.
        if (!args.getNonOptionArgs().contains(OperatorCommands.GRANT_INSTANCE_ADMIN)) {
            return;
        }

        String email;
        try {
            email = required(args, "email");
        } catch (IllegalArgumentException e) {
            // System.out, not the logger: an operator reading a usage message should not
            // have to look past a timestamp and a thread name to find it.
            System.out.println(e.getMessage());
            System.out.println();
            System.out.println(USAGE);
            exit(EXIT_USAGE);
            return;
        }

        try {
            exit(report(service.grant(email, null), email));
        } catch (RuntimeException e) {
            log.error("Granting Instance Admin to {} failed; the transaction rolled back and "
                    + "no grant was created.", email, e);
            exit(EXIT_FAILED);
        }
    }

    private int report(GrantInstanceAdminResult result, String email) {
        return switch (result.outcome()) {
            case GRANTED -> {
                System.out.println("User id: " + result.userId().orElseThrow());
                System.out.println("Name: " + result.canonicalName().orElseThrow());
                System.out.println();
                System.out.println(email + " is now an Instance Admin — every permission, "
                        + "every company on this instance.");
                pendingAcceptanceNote(result);
                yield EXIT_OK;
            }
            case ALREADY_HELD -> {
                System.out.println("User id: " + result.userId().orElseThrow());
                System.out.println();
                System.out.println(email + " already holds Instance Admin. Nothing was "
                        + "written, and nothing needed to be.");
                pendingAcceptanceNote(result);
                yield EXIT_OK;
            }
            case NO_SUCH_USER -> {
                System.out.println("No account on this instance has the address '" + email
                        + "'. Nothing was granted.");
                System.out.println("This command promotes an existing account. Create one "
                        + "first with:");
                System.out.println();
                System.out.println("  provision-company --slug=<slug> --name=<name> \\");
                System.out.println("                    --admin-email=" + email
                        + " --admin-name=<name>");
                yield EXIT_FAILED;
            }
            case USER_DEACTIVATED -> {
                System.out.println(email + " is DEACTIVATED. Nothing was granted.");
                System.out.println("The grant would confer nothing today — SYSTEM "
                        + "permissions resolve only for an ACTIVE account — and everything "
                        + "the moment anyone reactivated them, with no second look. "
                        + "Reactivate the account first if that is what you meant.");
                yield EXIT_FAILED;
            }
        };
    }

    /**
     * The one thing an operator is most likely to be confused by: the grant landed, the
     * account still cannot do anything, and nothing said why.
     */
    private void pendingAcceptanceNote(GrantInstanceAdminResult result) {
        if (!result.active().orElse(true)) {
            System.out.println();
            System.out.println("NOTE: this account has not accepted its invitation yet, so "
                    + "the grant is inert until it does. No further command is needed — it "
                    + "takes effect on acceptance.");
        }
    }

    private static String required(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Missing required argument --" + option);
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("--" + option + " was given more than once");
        }
        String value = values.getFirst();
        return Optional.ofNullable(value)
                .filter(v -> !v.isBlank())
                .orElseThrow(() ->
                        new IllegalArgumentException("Missing required argument --" + option));
    }

    /**
     * Closes the context with a status. {@code SpringApplication.exit} runs shutdown hooks
     * before returning the code, so the connection pool is drained rather than abandoned.
     */
    private void exit(int code) {
        System.exit(SpringApplication.exit(context, (ExitCodeGenerator) () -> code));
    }
}
