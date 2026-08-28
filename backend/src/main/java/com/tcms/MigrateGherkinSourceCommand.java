package com.tcms;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.MembershipStatus;
import com.tcms.provisioning.OperatorCommands;
import com.tcms.repository.GherkinBackfill;
import com.tcms.repository.GherkinBackfillReport;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code migrate-gherkin-source} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar migrate-gherkin-source --slug=acme --actor=ops@acme.test --dry-run
 * }</pre>
 *
 * <p>Parsing, actor resolution and reporting only — the migration itself is
 * {@code GherkinBackfill}, the same split {@code ExportCompanyCommand} and its three siblings use.
 *
 * <p>Inert unless asked: an {@link ApplicationRunner} runs on every boot, including under
 * {@code @SpringBootTest}. See {@code ProvisionCompanyCommand} for why the argument check is the
 * mechanism rather than a profile exclusion.
 *
 * <h2>What it is for</h2>
 *
 * <p>A {@code GHERKIN} case authored before §255 has its whole procedure in one blob and no step
 * rows, so a tester executing it records one verdict for the entire scenario — there is no
 * {@code test_step} for a per-step result to point at. §255 fixed the shape for every case written
 * after it. This is the only thing that fixes the ones written before, and until it has run
 * everywhere with a clean report, initiative {@code 002-} cannot drop the column.
 *
 * <h2>Why this one command is in the composition root and its four siblings are not</h2>
 *
 * <p>The other four operator verbs live in {@code com.tcms.provisioning}, and they can: everything
 * they compose is a foundation module. This one needs {@code repository} — the migration is
 * {@link GherkinBackfill}, and it has to be, because what a step is, what normalises, what the
 * content hash covers and when a version is new are decisions that must exist in exactly one place
 * ({@code CaseEdit.of}'s header makes that argument for a bulk action; this is the same argument
 * for a command). {@code repository} is not a foundation module and must not become one:
 * {@code execution}'s whole design rests on not being allowed to depend on it (plan §3).
 *
 * <p>So the edge {@code provisioning → repository} is one two rules refuse — Modulith's declared
 * dependencies and {@code ArchitectureRulesTest}'s cross-module rule — and weakening either for one
 * command would create the first sanctioned non-foundation edge in the codebase, which is a thing
 * the next author cites rather than a thing they read. The composition root is the mechanism that
 * already exists for exactly this: it is not a module, it wires modules together by definition, and
 * {@code ArchitectureRulesTest}'s own leaf rule names the pattern — <em>"a future admin API calls
 * CompanyProvisioningService from the composition root — it does not grow its own"</em>. The verb
 * is still registered in {@link OperatorCommands} with the other four, which is the list that
 * matters at runtime; only the file sits elsewhere, and this paragraph is why.
 *
 * <h2>{@code CompanyContext} is opened OUTSIDE the transactions</h2>
 *
 * <p>{@code CompanyAwareTransactionManager} reads the context in {@code doBegin}, so a context
 * opened inside a transaction arrives after that transaction has already decided there is no
 * company: the RLS GUC stays unset, every policy evaluates {@code company_id = NULL}, and the
 * migration reads zero rows and reports a clean nothing. {@code ExportCompanyCommand} carries the
 * long form of this argument; this is the second operator command that needs a company at all.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — every case that was on the blob now has rows (or already did). This is the
 *       state {@code 002-} is gated on, which is why it is a code and not a sentence.</li>
 *   <li>{@code 3} — <strong>the run finished and some cases are still on the blob</strong>: they
 *       did not parse, or they hold a construct this product does not import yet. Each is listed
 *       with its key and its reason, their text is untouched, and re-running after a human has
 *       fixed them is safe. Distinct from 0 because the release gate must be able to tell the two
 *       apart without reading prose, and distinct from 1 because the migration DID run and its
 *       successes are committed. {@code ExportCompanyCommand}'s "incomplete" code, for the same
 *       reason.</li>
 *   <li>{@code 2} — usage error: a missing argument, or an {@code --actor} that is not an active
 *       member of {@code --slug}'s company. Nothing was migrated.</li>
 *   <li>{@code 1} — the slug names no company on this instance, or the run failed. Whatever had
 *       already committed stands; a re-run skips it as {@code already has steps}.</li>
 * </ul>
 */
@Component
public class MigrateGherkinSourceCommand implements ApplicationRunner {

    static final String COMMAND = OperatorCommands.MIGRATE_GHERKIN_SOURCE;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_INCOMPLETE = 3;

    private static final String USAGE = """
            Usage:
              migrate-gherkin-source --slug=<slug> --actor=<email> [--dry-run]

            Gives every Gherkin case in one company the step rows it should have had. Cases
            authored before the unified step model kept their whole scenario in one text column
            and have no steps, so executing one records a single verdict instead of a verdict per
            step. This parses that text into rows and writes a new case version authored by
            --actor.

            --actor must be an ACTIVE member of --slug's company, and must be able to SEE every
            project being migrated: the version snapshot records the case's classification, and a
            snapshot rendered by somebody without the grant would record it as unclassified. A
            Company Admin satisfies both. Cases in a project the actor cannot see are listed and
            left alone.

            gherkin_source is NOT cleared. It is the proof the migration was faithful; a later
            release drops the column once every instance reports nothing left on it.

            SAFE TO RE-RUN. A case that already has steps is listed as such and untouched.
            --dry-run reports exactly what a real run would do and writes nothing.

            Exit 3 means the run finished and some cases are still on the blob — they did not
            parse, or they hold a Scenario Outline or several scenarios. Each is listed with the
            line to look at. Exit 2 means nothing ran.
            """;

    private static final Logger log = LoggerFactory.getLogger(MigrateGherkinSourceCommand.class);

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final GherkinBackfill backfill;
    private final ApplicationContext context;

    public MigrateGherkinSourceCommand(
            CompanyRepository companies,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            GherkinBackfill backfill,
            ApplicationContext context) {

        this.companies = companies;
        this.users = users;
        this.memberships = memberships;
        this.backfill = backfill;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND)) {
            return;
        }

        Arguments parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException e) {
            // System.out, not the logger: an operator reading a usage message should not have to
            // look past a timestamp and a thread name to find it. ExportCompanyCommand's shape.
            System.out.println(e.getMessage());
            System.out.println();
            System.out.println(USAGE);
            exit(EXIT_USAGE);
            return;
        }

        try {
            Resolution resolution = resolve(parsed);
            if (!(resolution instanceof Resolution.Ready ready)) {
                System.out.println(resolution.message());
                exit(exitCodeOf(resolution));
                return;
            }

            GherkinBackfillReport report;
            try (var ignored = CompanyContext.open(ready.companyId())) {
                report = backfill.run(ready.actorUserId(), parsed.dryRun());
            }
            exit(report(parsed, report));
        } catch (RuntimeException e) {
            log.error("Migrating '{}' failed. Whatever had already committed stands, and "
                    + "re-running after fixing the cause skips it.", parsed.slug(), e);
            exit(EXIT_FAILED);
        }
    }

    /**
     * What {@code --slug} and {@code --actor} resolved to — <strong>separated from {@link #run} so
     * it can be tested at all</strong>.
     *
     * <p>{@code run} ends in {@code System.exit}, which no in-process test can survive, so a
     * refusal reached only from there is a refusal nothing asserts. The three refusals below are
     * the ones an operator meets at 2am and the ones whose message has to name both values; each
     * carries its own sentence, and {@link #exitCodeOf} is the whole of the mapping {@code run}
     * applies. {@code GrantInstanceAdminService} makes the same split for the same reason — the
     * difference is only that its outcome type belongs to a service, and the service here is
     * {@code GherkinBackfill}, which answers a question about cases rather than about an operator's
     * two arguments.
     */
    sealed interface Resolution {

        /** The message the operator sees. Every refusal names both {@code --actor} and the slug. */
        String message();

        /** No account on this instance has that address. Nothing was opened. */
        record NoSuchActor(String message) implements Resolution {
        }

        /** No company has that slug. Nothing was opened. */
        record NoSuchCompany(String message) implements Resolution {
        }

        /** The account exists and is not an ACTIVE member here, so it cannot author a version. */
        record NotAMember(String message) implements Resolution {
        }

        record Ready(UUID companyId, UUID actorUserId) implements Resolution {

            @Override
            public String message() {
                return "";
            }
        }
    }

    /**
     * Resolves both arguments, and nothing else. <strong>Writes nothing</strong>, whichever way it
     * answers.
     */
    Resolution resolve(Arguments parsed) {
        // `app_user` is instance-level and carries no RLS, so the actor resolves before there is
        // any company to open — and an address that names nobody is refused with nothing started.
        // GrantInstanceAdminService resolves the same way for the same reason.
        AppUser actor = users.findByEmail(parsed.actor()).orElse(null);
        if (actor == null) {
            return new Resolution.NoSuchActor("No account on this instance has the address '"
                    + parsed.actor() + "', so there is nobody to author the migration of '"
                    + parsed.slug() + "'.");
        }

        // `company` is instance-level too, so the slug must resolve before there is anything to
        // scope to.
        Company company = companies.findBySlug(parsed.slug()).orElse(null);
        if (company == null) {
            return new Resolution.NoSuchCompany("No company on this instance has the slug '"
                    + parsed.slug() + "', so '" + parsed.actor() + "' has nothing to migrate.");
        }

        try (var ignored = CompanyContext.open(company.getId())) {
            // The membership IS company-owned and under RLS, so this is the first read that needs
            // a context — which is why the check is here and not beside the two instance-level
            // lookups above.
            //
            // ACTIVE, not "a row exists": an INVITED membership conveys nothing (see
            // CompanyMembershipRepository), and attributing a version to somebody who has not
            // accepted an invitation would put a name in the history that never agreed to be there.
            if (!memberships.existsByCompanyIdAndUserIdAndStatus(
                    company.getId(), actor.getId(), MembershipStatus.ACTIVE)) {
                return new Resolution.NotAMember("'" + parsed.actor() + "' is not an active member "
                        + "of '" + parsed.slug() + "', so it cannot author that company's case "
                        + "versions. Nothing was migrated.");
            }
        }
        return new Resolution.Ready(company.getId(), actor.getId());
    }

    /**
     * The exit code a refusal costs.
     *
     * <p>Two of the three are {@code 2} — a usage error, because the operator typed something that
     * does not name a thing — and the missing company is {@code 1}, which is
     * {@code ExportCompanyCommand}'s answer to the same question. Keeping them apart matters to a
     * script: an unknown slug is a typo in the invocation, and an actor without a membership is a
     * grant somebody has to make.
     */
    static int exitCodeOf(Resolution resolution) {
        return switch (resolution) {
            case Resolution.NoSuchActor ignored -> EXIT_USAGE;
            case Resolution.NotAMember ignored -> EXIT_USAGE;
            case Resolution.NoSuchCompany ignored -> EXIT_FAILED;
            case Resolution.Ready ignored -> EXIT_OK;
        };
    }

    /** The two required arguments and the one flag. */
    record Arguments(String slug, String actor, boolean dryRun) {
    }

    static Arguments parse(ApplicationArguments args) {
        // `--dry-run` is a flag, so presence is the whole question — `--dry-run=false` is refused
        // rather than honoured, because an operator who typed it means the opposite of what
        // ignoring the value would do.
        if (args.containsOption("dry-run")) {
            List<String> values = args.getOptionValues("dry-run");
            if (values != null && !values.isEmpty()) {
                throw new IllegalArgumentException("--dry-run takes no value");
            }
        }
        return new Arguments(
                required(args, "slug"), required(args, "actor"), args.containsOption("dry-run"));
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
     * One line per case, then the totals — and yields the exit code.
     *
     * <p>Every count is printed even when it is zero, for the reason
     * {@code ExportCompanyCommand} prints its orphan count even at zero: a number an operator sees every run is a number they notice when it changes, and a
     * line that appears only on the bad run is a line nobody has a baseline for.
     */
    private int report(Arguments parsed, GherkinBackfillReport report) {
        String verb = report.dryRun() ? "would-migrate" : "migrated";

        System.out.println("migrate-gherkin-source — company '" + parsed.slug() + "'"
                + (report.dryRun() ? " (dry run — nothing was written)" : ""));
        System.out.println();

        for (GherkinBackfillReport.Entry entry : report.entries()) {
            System.out.println(switch (entry.outcome()) {
                case MIGRATED -> "  " + entry.caseKey() + " " + verb
                        + " (" + entry.steps() + " steps)"
                        + (entry.note() == null ? "" : "; " + entry.note());
                case ALREADY_MIGRATED, NOT_MIGRATED ->
                        "  " + entry.caseKey() + " skipped: " + entry.reason();
            });
        }

        System.out.println();
        System.out.println("  " + report.entries().size() + " case(s) found on gherkin_source: "
                + report.countOf(GherkinBackfillReport.Outcome.MIGRATED) + " " + verb + ", "
                + report.countOf(GherkinBackfillReport.Outcome.ALREADY_MIGRATED)
                + " already had steps, "
                + report.stillOnTheBlob() + " still on the blob.");

        if (report.stillOnTheBlob() == 0) {
            return EXIT_OK;
        }
        System.out.println();
        System.out.println("INCOMPLETE — " + report.stillOnTheBlob() + " case(s) still hold their "
                + "procedure only in gherkin_source. Their text is unchanged and the app still "
                + "shows it; fix each one above and run this again.");
        return EXIT_INCOMPLETE;
    }

    /**
     * Closes the context with a status. {@code SpringApplication.exit} runs shutdown hooks before
     * returning the code, so the connection pool is drained rather than abandoned.
     */
    private void exit(int code) {
        System.exit(org.springframework.boot.SpringApplication.exit(
                context, (ExitCodeGenerator) () -> code));
    }
}
