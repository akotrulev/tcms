package com.tcms.provisioning;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code provision-company} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar provision-company \
 *     --slug=acme \
 *     --name="Acme Corporation" \
 *     --admin-email=ops@acme.test \
 *     --admin-name="A. Kotrulev" \
 *     [--project-key=OLB --project-name="Acme Online Banking"]
 * }</pre>
 *
 * <p>Parsing and reporting only — every decision lives in
 * {@link CompanyProvisioningService}, so a future admin API reuses the behaviour without
 * reusing the argument handling.
 *
 * <h2>Why the runner is inert unless asked</h2>
 *
 * <p>An {@link ApplicationRunner} runs on every boot, including a normal server start. It
 * checks for the {@code provision-company} non-option argument and returns immediately
 * otherwise, so the class is a no-op in the running application. {@code TcmsApplication}
 * separately switches the application type to non-web for this argument, because a CLI
 * run that started a servlet container would provision correctly and then never exit.
 *
 * <p>That guard is also what keeps it inert under {@code @SpringBootTest}, which
 * <em>does</em> execute {@link ApplicationRunner} beans — a runner that provisioned on
 * context startup would seed a company into the shared Testcontainers database that every
 * other test class then has to account for, and would call {@link System#exit} inside the
 * Gradle test JVM. A {@code @Profile("!test")} exclusion was considered and left out: no
 * test activates a {@code test} profile here, so it would have read as a safety mechanism
 * while doing nothing. The argument check is the mechanism.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — provisioned, or already provisioned (both are success; a top-up run
 *       is a supported use)</li>
 *   <li>{@code 2} — usage error: a missing or malformed argument. Distinct from 1 so a
 *       deployment script can tell "you typed it wrong" from "it did not work"</li>
 *   <li>{@code 1} — the provisioning transaction failed and rolled back</li>
 * </ul>
 */
@Component
public class ProvisionCompanyCommand implements ApplicationRunner {

    static final String COMMAND = "provision-company";

    /**
     * Whether these raw {@code main} arguments invoke this command — asked by
     * {@code TcmsApplication} before the context is built, to run non-web.
     *
     * <p>Lives here rather than as a literal in {@code main} so the command name is
     * spelled once. Two spellings that must agree, in two modules, is how a rename ships
     * a CLI that provisions correctly and then hangs on a servlet container nobody asked
     * for.
     */
    public static boolean isRequestedBy(String[] args) {
        for (String arg : args) {
            if (COMMAND.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private static final String USAGE = """
            Usage:
              provision-company --slug=<slug> --name=<display name> \\
                                --admin-email=<email> --admin-name=<display name> \\
                                [--project-key=<KEY> --project-name=<name>]

            Creates a client company, its classification vocabularies and display-key
            counters, an optional first project, and invites the first Company Admin.

            Idempotent: re-running against an existing slug tops it up and changes nothing
            that already exists. It never renames or overwrites.
            """;

    private static final Logger log = LoggerFactory.getLogger(ProvisionCompanyCommand.class);

    private final CompanyProvisioningService provisioning;
    private final ApplicationContext context;

    public ProvisionCompanyCommand(
            CompanyProvisioningService provisioning, ApplicationContext context) {
        this.provisioning = provisioning;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND)) {
            return;
        }

        ProvisionCompanyRequest request;
        try {
            request = parse(args);
        } catch (IllegalArgumentException e) {
            // System.out, not the logger: this is the CLI's own output, and an operator
            // reading a usage message should not have to look past a timestamp and a
            // thread name to find it.
            System.out.println(e.getMessage());
            System.out.println();
            System.out.println(USAGE);
            exit(EXIT_USAGE);
            return;
        }

        try {
            report(provisioning.provision(request));
            exit(EXIT_OK);
        } catch (RuntimeException e) {
            log.error("Provisioning '{}' failed; the transaction rolled back and nothing was "
                    + "created. Re-running after fixing the cause is safe.", request.slug(), e);
            exit(EXIT_FAILED);
        }
    }

    /**
     * Both project arguments are required together. Accepting one alone would provision a
     * company whose first project is named after its key or keyed from its name — a guess
     * the operator did not make, in a field that is immutable afterwards.
     */
    static ProvisionCompanyRequest parse(ApplicationArguments args) {
        String slug = required(args, "slug");
        String name = required(args, "name");
        String adminEmail = required(args, "admin-email");
        String adminName = required(args, "admin-name");

        Optional<String> projectKey = optional(args, "project-key");
        Optional<String> projectName = optional(args, "project-name");

        if (projectKey.isPresent() != projectName.isPresent()) {
            throw new IllegalArgumentException(
                    "--project-key and --project-name go together: give both to create a first "
                            + "project, or neither to provision the company without one.");
        }

        if (projectKey.isPresent()) {
            return ProvisionCompanyRequest.withFirstProject(
                    slug, name, adminEmail, adminName, projectKey.get(), projectName.get());
        }
        return ProvisionCompanyRequest.of(slug, name, adminEmail, adminName);
    }

    private static String required(ApplicationArguments args, String option) {
        return optional(args, option).orElseThrow(() ->
                new IllegalArgumentException("Missing required argument --" + option));
    }

    private static Optional<String> optional(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("--" + option + " was given more than once");
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Reports per part rather than "done", because a top-up run and a fresh one are both
     * successes and must not read alike — an operator who meant to create a new client and
     * sees "already existed" everywhere has typed a slug that belongs to another one.
     */
    private void report(ProvisionCompanyResult result) {
        Map<String, Boolean> parts = new java.util.LinkedHashMap<>();
        parts.put("company", result.companyCreated());
        parts.put("admin account", result.adminUserCreated());
        parts.put("company membership (invited)", result.membershipCreated());
        parts.put("Company Admin grant", result.adminGrantCreated());
        if (result.projectId().isPresent()) {
            parts.put("first project", result.projectCreated());
        }

        System.out.println("Company id: " + result.companyId());
        System.out.println("Admin user id: " + result.adminUserId());
        result.projectId().ifPresent(id -> System.out.println("Project id: " + id));
        parts.forEach((part, created) ->
                System.out.println("  " + (created ? "created " : "existed ") + part));

        if (result.wasNoOp()) {
            System.out.println();
            System.out.println("Nothing was created — this company was already provisioned. "
                    + "Vocabularies and key counters were topped up if any were missing.");
        }
        System.out.println();
        reportInvitation(result);
    }

    /**
     * Prints the activation link.
     *
     * <p><strong>Printed even though the invitation is also emailed</strong>, and that is
     * deliberate: mail is sent after the transaction commits and can fail — a wrong relay,
     * a DNS problem, a greylisting server — and if the only copy of the link were in that
     * message, a mail outage would mean a provisioned company nobody can enter. The console
     * is the operator's fallback, and it degrades to hand delivery rather than to a
     * re-provision.
     *
     * <p>The three cases are distinct because they need different actions from the operator,
     * and "invitation: ok" would hide the one that needs any.
     */
    private void reportInvitation(ProvisionCompanyResult result) {
        if (result.invitationLink().isPresent()) {
            System.out.println("The administrator is INVITED, not active: they hold no "
                    + "permission until they accept and set a password.");
            System.out.println("Give them this link — it is single-use and expires "
                    + result.invitationExpiry().orElseThrow() + ":");
            System.out.println();
            System.out.println("  " + result.invitationLink().orElseThrow());
        } else if (result.invitationExpiry().isPresent()) {
            System.out.println("An invitation is already outstanding and was NOT replaced, so "
                    + "the link already sent still works. It expires "
                    + result.invitationExpiry().orElseThrow() + "; re-run after that to issue a "
                    + "fresh one.");
            System.out.println("The existing link cannot be reprinted — only a digest of it is "
                    + "stored, which is what makes the database unable to hand out a working "
                    + "token.");
        } else {
            System.out.println("The administrator has already accepted; no invitation was "
                    + "issued. They sign in with their existing password.");
        }
    }

    /**
     * Closes the context with a status. {@code SpringApplication.exit} runs shutdown hooks
     * before returning the code, so the connection pool is drained rather than abandoned —
     * which a bare {@code System.exit} would do mid-transaction if one were still open.
     */
    private void exit(int code) {
        System.exit(org.springframework.boot.SpringApplication.exit(
                context, (ExitCodeGenerator) () -> code));
    }
}
