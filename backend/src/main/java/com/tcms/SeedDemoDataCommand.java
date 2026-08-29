package com.tcms;

import com.tcms.provisioning.OperatorCommands;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code seed-demo-data} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar seed-demo-data [--force]
 * }</pre>
 *
 * <p>Fills an instance with two client companies' worth of realistic content, so that every screen
 * can be looked at against real data rather than against an empty state. Parsing and reporting
 * only — the seeding itself is {@link DemoDataSeeder}, the same split the five operator verbs use.
 *
 * <h2>Why it lives in the composition root</h2>
 *
 * <p>Same reason as {@link MigrateGherkinSourceCommand}, and more so: this drives write paths in
 * {@code repository}, {@code execution}, {@code documentation}, {@code reporting}, {@code work} and
 * {@code integration}. Nine of those edges are ones {@code provisioning} may not have, and the
 * composition root is the mechanism that already exists for a caller that wires modules together
 * rather than being one. Registered in {@link OperatorCommands} with the others, because that
 * registry is what decides the jar runs non-web — a verb missing from it succeeds and then hangs.
 *
 * <h2>It seeds through the real write paths, and that is the point</h2>
 *
 * <p>Nothing here writes a row it could have asked a service for. A case is created by
 * {@code CaseCreateService}, so it gets a real display key from the company's {@code key_sequence},
 * a real version row and a real content hash; a result is recorded by {@code ExecutionService}, so
 * the run's rollups move the way they do in production. Seed data assembled by INSERT looks right
 * on the screen it was written for and disagrees with the application everywhere else — which is
 * the exact defect this command exists to help find, and would therefore be the worst thing for it
 * to contain.
 *
 * <h2>There is no {@code --reset}</h2>
 *
 * <p>Deliberate: a verb that deletes a company's content ships in the same image as production and
 * would be one mistyped flag from doing it there. Instead the run is <strong>idempotent</strong> —
 * provisioning already is, and each project's content is skipped when that project already holds
 * cases. {@code --force} adds content to a project that already has some, which is what you want
 * while extending the fixture and never what you want twice. To start clean locally, drop the
 * volume: {@code docker compose down -v}.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — every company, person and project asked for now exists.</li>
 *   <li>{@code 2} — usage error. Nothing was written.</li>
 *   <li>{@code 1} — the run failed. Whatever had already committed stands, and re-running tops
 *       the rest up rather than duplicating it.</li>
 * </ul>
 */
@Component
public class SeedDemoDataCommand implements ApplicationRunner {

    static final String COMMAND = OperatorCommands.SEED_DEMO_DATA;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private static final String FORCE_FLAG = "force";

    private static final String USAGE = """
            Usage:
              seed-demo-data [--force]

            Fills this instance with two demo companies and everything under them — people at
            every role, projects, suites, cases with steps, plans, runs, executions with per-step
            results, evidence, defects, documentation, reports, board items and notifications —
            so that every screen can be looked at against real data.

            Everything is created through the same services the API calls, so display keys,
            versions, content hashes and rollups are the real ones.

            SAFE TO RE-RUN. A company that exists is topped up rather than recreated, and a
            project that already holds cases is left alone. --force adds a second helping of
            content to a project that already has some; use it while extending the fixture.

            There is no --reset. This ships in the production image. To start clean locally,
            drop the volumes: docker compose down -v
            """;

    private static final Logger log = LoggerFactory.getLogger(SeedDemoDataCommand.class);

    private final DemoDataSeeder seeder;
    private final ApplicationContext context;

    public SeedDemoDataCommand(DemoDataSeeder seeder, ApplicationContext context) {
        this.seeder = seeder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND)) {
            return;
        }

        List<String> unknown = args.getOptionNames().stream()
                .filter(name -> !FORCE_FLAG.equals(name))
                .toList();
        if (!unknown.isEmpty()) {
            // System.out, not the logger: an operator reading a usage message should not have to
            // look past a timestamp and a thread name to find it.
            System.out.println("Unrecognised option(s): " + String.join(", ", unknown));
            System.out.println();
            System.out.println(USAGE);
            exit(EXIT_USAGE);
            return;
        }

        boolean force = args.containsOption(FORCE_FLAG);
        try {
            DemoSeedReport report = seeder.seed(force);
            System.out.println(report.render());
            exit(EXIT_OK);
        } catch (RuntimeException e) {
            log.error("Seeding failed. Whatever had already committed stands; re-running tops up "
                    + "the rest rather than duplicating it.", e);
            exit(EXIT_FAILED);
        }
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
