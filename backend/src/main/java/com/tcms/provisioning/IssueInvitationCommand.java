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
 * The {@code issue-invitation} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar issue-invitation --slug=acme --email=colleague@example.test
 * }</pre>
 *
 * <p>Prints a fresh activation link for a member who has been invited but has not accepted.
 * <strong>The previous link, if any, stops working.</strong>
 *
 * <p>This is the operator path the UI already promises. An instance with no SMTP relay tells
 * an invitee to "ask your operator for the activation link", and before this command there was
 * no way to answer: only a digest of the token is stored, so an undelivered link was
 * unrecoverable by anyone.
 *
 * <p>Parsing and reporting only — the behaviour lives in {@link IssueInvitationService}, the
 * same split {@link ProvisionCompanyCommand} uses, so an admin API can reuse one without the
 * other.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — a link was issued and printed</li>
 *   <li>{@code 2} — usage error: a missing or malformed argument</li>
 *   <li>{@code 1} — nothing was issued (unknown company, not a member, already active), or
 *       the transaction failed. <strong>Non-zero on "already active" deliberately</strong>:
 *       the operator asked for a link and did not get one, so a script must not read it as
 *       success.</li>
 * </ul>
 */
@Component
public class IssueInvitationCommand implements ApplicationRunner {

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private static final String USAGE = """
            Usage:
              issue-invitation --slug=<slug> --email=<address>

            Issues a NEW activation link for a member who has been invited but has not
            accepted yet, and prints it.

            Any invitation already outstanding for that member is REPLACED — the previous
            link stops working immediately. That is the point: only a digest of a token is
            stored, so an existing link can never be reprinted, and re-issuing is the only
            way to produce one that works.

            Use this when the instance has no SMTP relay configured, or when a message did
            not arrive. It does nothing for a member who has already accepted; they need a
            password reset, not an invitation.
            """;

    private static final Logger log = LoggerFactory.getLogger(IssueInvitationCommand.class);

    private final IssueInvitationService service;
    private final ApplicationContext context;

    public IssueInvitationCommand(IssueInvitationService service, ApplicationContext context) {
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Inert unless asked — an ApplicationRunner runs on every boot, including under
        // @SpringBootTest. See ProvisionCompanyCommand for why the argument check is the
        // mechanism rather than a profile exclusion.
        if (!args.getNonOptionArgs().contains(OperatorCommands.ISSUE_INVITATION)) {
            return;
        }

        String slug;
        String email;
        try {
            slug = required(args, "slug");
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
            exit(report(service.issue(slug, email, null), slug, email));
        } catch (RuntimeException e) {
            log.error("Issuing an invitation for {} in company '{}' failed; the transaction "
                    + "rolled back and no token was created.", email, slug, e);
            exit(EXIT_FAILED);
        }
    }

    private int report(IssueInvitationResult result, String slug, String email) {
        return switch (result.outcome()) {
            case ISSUED -> {
                System.out.println("Company id: " + result.companyId().orElseThrow());
                System.out.println("User id: " + result.userId().orElseThrow());
                result.displayName().ifPresent(n -> System.out.println("Name: " + n));
                System.out.println();
                System.out.println("A NEW invitation was issued. Any link previously sent to "
                        + email + " no longer works.");
                System.out.println("Give them this link — it is single-use and expires "
                        + result.expiresAt().orElseThrow() + ":");
                System.out.println();
                System.out.println("  " + result.link().orElseThrow());
                System.out.println();
                System.out.println("This is the only time it can be read. Only a digest is "
                        + "stored, so it cannot be reprinted — re-run this command to issue "
                        + "another.");
                yield EXIT_OK;
            }
            case NO_SUCH_COMPANY -> {
                System.out.println("No company has the slug '" + slug + "'. Nothing was issued.");
                System.out.println("List them with: SELECT slug FROM company;");
                yield EXIT_FAILED;
            }
            case NOT_A_MEMBER -> {
                System.out.println(email + " is not a member of '" + slug + "'. Nothing was "
                        + "issued.");
                System.out.println("Invite them from the UI first — this command re-issues a "
                        + "link for an existing invitation, it does not create memberships.");
                yield EXIT_FAILED;
            }
            case ALREADY_ACTIVE -> {
                System.out.println(email + " has already accepted and is an ACTIVE member of '"
                        + slug + "'. Nothing was issued.");
                System.out.println("They sign in with their existing password. An invitation "
                        + "would not help, and issuing one for a live account is deliberately "
                        + "not possible.");
                yield EXIT_FAILED;
            }
        };
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
