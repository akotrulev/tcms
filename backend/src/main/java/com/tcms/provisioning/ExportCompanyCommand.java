package com.tcms.provisioning;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code export-company} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar export-company --slug=acme --out=/var/backups/acme.zip
 * }</pre>
 *
 * <p>Parsing and reporting only — every decision lives in {@link ExportCompanyService}, the same
 * split {@link ProvisionCompanyCommand}, {@link IssueInvitationCommand} and
 * {@link GrantInstanceAdminCommand} use.
 *
 * <p>Inert unless asked: an {@link ApplicationRunner} runs on every boot, including under
 * {@code @SpringBootTest}. See {@link ProvisionCompanyCommand} for why the argument check is the
 * mechanism rather than a profile exclusion.
 *
 * <h2>The one line in this class that carries the whole area</h2>
 *
 * <p>{@code CompanyContext.open(...)} wraps the call to {@link ExportCompanyService#export} from
 * <strong>outside</strong>. {@code CompanyAwareTransactionManager} reads the context in
 * {@code doBegin}, so a context opened inside that {@code @Transactional} method would arrive after
 * the transaction had already decided there was no company: the GUC stays unset, every
 * row-level-security policy evaluates {@code company_id = NULL}, and the walk reads zero rows from
 * every company-owned table without raising anything. This is also the first operator command that
 * needs a company context at all — its three siblings run instance-level, with none open.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — a complete archive is at {@code --out}</li>
 *   <li>{@code 3} — <strong>an archive is at {@code --out} and it is incomplete</strong>: one or
 *       more rows name bytes that are not on disk. Enumerated in manifest.json and on
 *       stdout. Distinct from 0 because an offboarding script must be able to tell a complete copy
 *       from the best available one, and distinct from 1 because there IS a file and it is worth
 *       keeping</li>
 *   <li>{@code 2} — usage error: a missing or malformed argument</li>
 *   <li>{@code 1} — nothing was written. The walk refused (no company context, a sentinel table
 *       empty, a table nothing classifies), the slug names no company, or the transaction failed.
 *       <strong>No file exists at {@code --out}</strong></li>
 * </ul>
 */
@Component
public class ExportCompanyCommand implements ApplicationRunner {

    static final String COMMAND = OperatorCommands.EXPORT_COMPANY;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_INCOMPLETE = 3;

    private static final String USAGE = """
            Usage:
              export-company --slug=<slug> --out=<path to a .zip>

            Writes one company's entire dataset — every company-owned table, plus the evidence,
            report and raw-ingestion files those rows name — to a single zip archive. For
            offboarding a client and for answering a data-protection request.

            READ-ONLY. Nothing in the database is changed, and running it twice is safe.

            What the archive does NOT contain, deliberately: password hashes, API-token digests,
            invitation digests, notification-channel secrets and identity-provider client secrets
            and bind passwords. manifest.json lists every redaction and every table left out, with
            the reason. See the self-hosting runbook.

            The file appears at --out only if the export finished. A partial run leaves
            <out>.partial instead, which is a diagnosis rather than a deliverable.

            Exit 3 means an archive was written and something it references is missing from disk;
            manifest.json names each one. Exit 1 means no file was written at all.
            """;

    private static final Logger log = LoggerFactory.getLogger(ExportCompanyCommand.class);

    private final CompanyRepository companies;
    private final ExportCompanyService exportService;
    private final ApplicationContext context;

    public ExportCompanyCommand(
            CompanyRepository companies,
            ExportCompanyService exportService,
            ApplicationContext context) {
        this.companies = companies;
        this.exportService = exportService;
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
            // look past a timestamp and a thread name to find it.
            System.out.println(e.getMessage());
            System.out.println();
            System.out.println(USAGE);
            exit(EXIT_USAGE);
            return;
        }

        try {
            // No company context: `company` is instance-level and carries no RLS, so the slug must
            // be resolvable before there is anything to scope to.
            Company company = companies.findBySlug(parsed.slug()).orElse(null);
            if (company == null) {
                System.out.println(
                        "No company on this instance has the slug '" + parsed.slug() + "'.");
                exit(EXIT_FAILED);
                return;
            }
            ExportCompanyResult result;
            try (var ignored = CompanyContext.open(company.getId())) {
                result = exportService.export(company, Path.of(parsed.out()));
            }
            exit(report(result));
        } catch (RuntimeException e) {
            log.error("Exporting '{}' failed; no archive was written and nothing was changed. "
                    + "Re-running after fixing the cause is safe.", parsed.slug(), e);
            exit(EXIT_FAILED);
        }
    }

    /** The two arguments, both required. */
    record Arguments(String slug, String out) {
    }

    static Arguments parse(ApplicationArguments args) {
        return new Arguments(required(args, "slug"), required(args, "out"));
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
     * Reports per part, the way {@code provision-company} does, and yields the exit code —
     * {@link GrantInstanceAdminCommand}'s shape.
     *
     * <p>The orphan count is printed even when it is zero. A number an operator sees every run is a
     * number they notice when it changes; a line that appears only on the bad run is a line nobody
     * has a baseline for.
     */
    private int report(ExportCompanyResult result) {
        System.out.println("Archive: " + result.archive() + " (" + result.bytes() + " bytes)");
        System.out.println("  " + result.tableRows().size() + " tables, "
                + result.totalRows() + " rows");
        System.out.println("  " + result.blobCount() + " files");
        System.out.println("  " + result.orphans().size()
                + " unreferenced file(s) under this company's prefix, listed in manifest.json and "
                + "NOT archived");

        if (result.wasComplete()) {
            return EXIT_OK;
        }
        System.out.println();
        System.out.println("INCOMPLETE — " + result.missing().size() + " row(s) name bytes that "
                + "are not on disk. The archive was written anyway and manifest.json lists each "
                + "one; this is the best available copy, not a complete one.");
        result.missing().forEach(m -> System.out.println("  " + m.table() + " " + m.id()));
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
